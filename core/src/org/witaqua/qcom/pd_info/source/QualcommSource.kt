/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.source

import org.witaqua.qcom.pd_info.io.Sysfs
import org.witaqua.qcom.pd_info.model.EmptyReason
import org.witaqua.qcom.pd_info.model.Origin
import org.witaqua.qcom.pd_info.model.Port
import org.witaqua.qcom.pd_info.model.PowerDeliveryObject
import org.witaqua.qcom.pd_info.model.Snapshot
import org.witaqua.qcom.pd_info.model.SourceCapability

/*
 * Qualcomm's own driver, drivers/usb/pd/policy_engine.c, which has carried the
 * same interface from msm8998 through the sm8xxx parts on 4.x and 5.4. It
 * publishes the data objects as the 32-bit words that went over the wire, and
 * it is the only one of the two that exposes the request - so where both exist
 * this is the richer read.
 */
object QualcommSource : PdSource {
    private const val CLASS = "/sys/class/usbpd"
    private const val OBJECTS = 7

    override fun present(sysfs: Sysfs) =
        sysfs.list(CLASS).any { sysfs.read("$CLASS/$it/contract") != null }

    override fun read(sysfs: Sysfs): Snapshot? {
        val ports = sysfs.list(CLASS).mapNotNull { name ->
            val directory = "$CLASS/$name"

            /*
             * One batch for the whole port: with a root shell every call is a
             * process, and this is sixteen files.
             */
            val values = sysfs.read(
                listOf("contract", "current_pr", "current_dr", "rdo")
                    .map { "$directory/$it" } +
                    (1..OBJECTS).map { "$directory/pdo$it" }
            )

            values["$directory/contract"] ?: return@mapNotNull null

            var seenFixed = false
            val capabilities = (1..OBJECTS).mapNotNull { position ->
                val word = values["$directory/pdo$position"]
                    ?.toLongOrNull(16)
                    ?.takeIf { it != 0L }
                    ?: return@mapNotNull null

                /* The source-wide bits are only meaningful in the first fixed one. */
                val first = !seenFixed && word ushr 30 and 0x3 == 0L
                seenFixed = seenFixed || first
                PowerDeliveryObject.capability(word, position, first)
            }

            val request = values["$directory/rdo"]
                ?.toLongOrNull(16)
                ?.takeIf { it != 0L }
                ?.let { word ->
                    val at = PowerDeliveryObject.requestPosition(word)
                    PowerDeliveryObject.request(
                        word,
                        capabilities.firstOrNull { it.position == at },
                    )
                }

            Port(
                /*
                 * This driver keeps its nodes whether or not anything is
                 * plugged in, and says so in the power role: neither end of a
                 * cable that is not there has one.
                 */
                attached = values["$directory/current_pr"]
                    ?.let { it != "none" } ?: false,
                name = name,
                powerRole = values["$directory/current_pr"],
                dataRole = values["$directory/current_dr"],
                contract = values["$directory/contract"],
                capabilities = capabilities,
                request = request,
            )
        }

        if (ports.isEmpty()) {
            return null
        }

        return Snapshot(
            origin = Origin.QUALCOMM,
            ports = ports,
            emptyReason = if (ports.none { it.attached }) {
                EmptyReason.NOTHING_ATTACHED
            } else {
                null
            },
        )
    }
}
