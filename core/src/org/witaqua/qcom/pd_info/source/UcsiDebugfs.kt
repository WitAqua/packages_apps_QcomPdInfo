/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.source

import org.witaqua.qcom.pd_info.io.Sysfs
import org.witaqua.qcom.pd_info.model.PowerDeliveryObject
import org.witaqua.qcom.pd_info.model.Request
import org.witaqua.qcom.pd_info.model.SourceCapability

/*
 * Asking the UCSI policy manager directly, over the debugfs interface the
 * driver exposes:
 *
 *   /sys/kernel/debug/usb/ucsi/<name>/command    a command word, written
 *   /sys/kernel/debug/usb/ucsi/<name>/response   its response, read back
 *
 * Why this exists. The usb_power_delivery class only carries the object lists
 * when the driver read them, and ucsi_get_pdos() refuses to read them unless
 * the firmware set UCSI_CAP_PDO_DETAILS. At least one platform - SM8850 with
 * pmic-glink, checked on a handset - answers GET_PDOS perfectly well while
 * reporting a features field of zero, so the driver never asks and the class
 * stays empty. debugfs's ucsi_cmd() does not consult that bit, so the question
 * can still be put.
 *
 * Only GET commands are sent. Nothing here changes the contract.
 *
 * Needs all three of: root, an SELinux context that may reach debugfs (the
 * shell's may not), and debugfs mounted - which it is not by default.
 */
object UcsiDebugfs {
    private const val ROOT = "/sys/kernel/debug/usb/ucsi"

    /* UCSI command opcodes and the fields each one takes. */
    private const val GET_CONNECTOR_STATUS = 0x12L
    private const val GET_PDOS = 0x10L

    private const val CONNECTOR_SHIFT = 16
    private const val PARTNER_SHIFT = 23
    private const val OFFSET_SHIFT = 24
    private const val NUM_PDOS_SHIFT = 32
    private const val SOURCE_PDOS = 1L shl 34

    /** A response carries at most four objects, which is what may be asked for. */
    private const val PER_REQUEST = 4

    /** Object positions are three bits wide, so there can be no more than this. */
    private const val MAX_OBJECTS = 7

    fun present(sysfs: Sysfs) = device(sysfs) != null

    /* One directory per policy manager, named after the device behind it. */
    private fun device(sysfs: Sysfs): String? =
        sysfs.list(ROOT).firstOrNull { sysfs.list("$ROOT/$it").contains("command") }

    /**
     * The charger's source capabilities on one connector, in advertised order,
     * or an empty list when the question cannot be put or is not answered.
     *
     * Connectors number from one, and which one is asked matters on a board
     * that has two of them - the type-C class counts its ports from zero, so
     * the number here is one more than the port's.
     */
    fun capabilities(sysfs: Sysfs, connector: Int): List<SourceCapability> {
        val name = device(sysfs) ?: return emptyList()
        val words = mutableListOf<Long>()

        /* Four at a time, because that is how many a response holds. */
        var offset = 0
        var seenFixed = false
        val capabilities = mutableListOf<SourceCapability>()

        objects@ while (offset < MAX_OBJECTS) {
            val count = minOf(PER_REQUEST, MAX_OBJECTS - offset)
            val response = send(
                sysfs,
                name,
                GET_PDOS or
                    (connector.toLong() shl CONNECTOR_SHIFT) or
                    (1L shl PARTNER_SHIFT) or
                    (offset.toLong() shl OFFSET_SHIFT) or
                    ((count - 1).toLong() shl NUM_PDOS_SHIFT) or
                    SOURCE_PDOS,
            ) ?: break

            for (index in 0 until count) {
                val word = response.getOrNull(index) ?: break@objects

                /*
                 * The list ends where the words stop being objects. How many
                 * are real is not in the response, so each candidate has to
                 * argue for itself: not zero, not one already seen, and it has
                 * to decode to a supply that could exist.
                 */
                if (word == 0L || word in words) {
                    break@objects
                }

                val position = words.size + 1
                val first = !seenFixed && word ushr 30 and 0x3 == 0L
                val capability = PowerDeliveryObject.capability(word, position, first)
                if (!PowerDeliveryObject.isPlausible(capability)) {
                    break@objects
                }

                words.add(word)
                capabilities.add(capability)
                seenFixed = seenFixed || first
            }

            offset += count
        }

        return capabilities
    }

    /**
     * The request in force on one connector. The class publishes no request
     * object at all, but the connector status carries one, which is where the
     * driver gets it from.
     */
    fun request(
        sysfs: Sysfs,
        connector: Int,
        capabilities: List<SourceCapability>,
    ): Request? {
        val name = device(sysfs) ?: return null
        val response = send(
            sysfs,
            name,
            GET_CONNECTOR_STATUS or (connector.toLong() shl CONNECTOR_SHIFT),
        ) ?: return null

        /* Bits 32..63 of the status, which is the second 32-bit word of it. */
        val word = response.getOrNull(1)?.takeIf { it != 0L } ?: return null
        val at = PowerDeliveryObject.requestPosition(word)

        return PowerDeliveryObject.request(word, capabilities.firstOrNull { it.position == at })
    }

    /**
     * Sends one command and reads the response back as 32-bit words, lowest
     * first. The driver prints the response as two or three 64-bit words - the
     * oldest kernels omit the extended one - so the tail is taken rather than
     * the head, and what is asked for here fits in the first two either way.
     */
    private fun send(sysfs: Sysfs, name: String, command: Long): List<Long>? {
        if (!sysfs.write("$ROOT/$name/command", "0x%x".format(command))) {
            return null
        }

        /*
         * A failed command leaves its status behind, and reading the response
         * then fails rather than returning stale values - which is the right
         * way round, and means an unreadable response is an answer.
         */
        val text = sysfs.read("$ROOT/$name/response")?.removePrefix("0x") ?: return null
        if (text.length < 32 || !text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return null
        }

        return text.takeLast(32)
            .chunked(8)
            .map { it.toLong(16) }
            .reversed()
    }
}
