/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.source

import org.witaqua.qcom.pd_info.KernelVersion
import org.witaqua.qcom.pd_info.Platform
import org.witaqua.qcom.pd_info.io.DirectSysfs
import org.witaqua.qcom.pd_info.io.RootSysfs
import org.witaqua.qcom.pd_info.io.Sysfs
import org.witaqua.qcom.pd_info.model.Origin
import org.witaqua.qcom.pd_info.model.Snapshot

/**
 * Picking where to read from.
 *
 * The platform and the kernel version decide the order to try in, because they
 * say which interface a board plausibly has: the upstream class arrived in
 * android14-6.1, and the driver that publishes the 32-bit objects is
 * qualcomm's, so a 5.4 qualcomm board can only have the latter and a 6.x board
 * from anyone else can only have the former.
 *
 * They do not decide the answer. A 6.12 board registers the upstream devices
 * and may leave them empty, and no version or model name distinguishes that
 * from one that fills them - it follows from whether the firmware reports PDO
 * details over UCSI, which is not published anywhere. So the ordering is a
 * hint and presence is the test: the first candidate that is actually there
 * and actually yields something wins, and a board with both gets the richer
 * one because that is the order the hint puts it in.
 */
object Sources {
    /**
     * The interfaces to try, best first. Exposed rather than private so the
     * screen can say what was considered when nothing is found.
     */
    fun candidates(): List<PdSource> {
        val kernel = Platform.kernelVersion
        val upstreamPossible = kernel == null || kernel >= KernelVersion.UPSTREAM_PD_CLASS

        return buildList {
            /*
             * Qualcomm's first where it is plausible: it is the only one of the
             * two that publishes the request object, so where a board has both
             * it is the better read. Its driver was never upstream, so on a
             * kernel new enough to have dropped it the probe simply misses.
             */
            if (Platform.looksQualcomm) {
                add(QualcommSource)
            }
            if (upstreamPossible) {
                add(UpstreamSource)
            }

            /*
             * Both, in the other order, for anything the reasoning above got
             * wrong - a vendor kernel that backported the class, a board whose
             * SoC name this does not recognise. Costs a directory test each.
             */
            if (!contains(UpstreamSource)) {
                add(UpstreamSource)
            }
            if (!contains(QualcommSource)) {
                add(QualcommSource)
            }

            /*
             * Last, because it is the one that cannot answer the question this
             * app is for: no object list exists anywhere on a board that gets
             * this far. It is still the difference between a screen that says
             * what the contract is and one that says nothing at all.
             */
            add(UcsiSupplySource)
        }
    }

    /**
     * Reads through [sysfs], trying each candidate in turn. A source that is
     * present but yields nothing usable does not stop the search - except when
     * it says why it is empty, which is an answer rather than a miss.
     */
    fun read(sysfs: Sysfs): Snapshot? {
        var empty: Snapshot? = null

        for (source in candidates()) {
            if (!source.present(sysfs)) {
                continue
            }

            val snapshot = source.read(sysfs)?.let { fillIn(sysfs, it) } ?: continue
            if (snapshot.hasAnything && snapshot.emptyReason == null) {
                return snapshot
            }

            /*
             * Keep the first interface that could explain itself, in case
             * nothing better turns up. "The devices are there and empty" is
             * worth telling the reader; "nothing here" is not.
             */
            if (empty == null) {
                empty = snapshot
            }
        }

        return empty
    }

    /**
     * Fills in what the upstream class leaves out, by asking the policy
     * manager directly.
     *
     * Two different gaps, and they close differently:
     *
     *   The object lists are missing only on a platform whose firmware does
     *   not report that it can list them. Dropping that check in the kernel
     *   fixes it for everyone - see the app's docs/kernel.md - after which
     *   there is nothing to ask for here.
     *
     *   The request object is missing always. The class has no attribute for
     *   it at any kernel version, and the only other place it appears is the
     *   connector status. So this is asked for whenever it can be, not only
     *   when the lists are absent: a patched kernel would otherwise publish
     *   the whole menu and never say which line was ordered.
     *
     * Qualcomm's own driver publishes both, so a board with it comes through
     * here untouched. Anything else is worth asking for, including the source
     * that has no objects of its own: a kernel that grew the debugfs interface
     * without growing the class - one patched the way
     * docs/5.10_xiaomi-sm8450.md describes - can then answer in full.
     *
     * In practice this makes the request a root-only figure on the upstream
     * path, since debugfs is what it comes from - which is a split the builds
     * already have rather than one this introduces.
     */
    private fun fillIn(sysfs: Sysfs, snapshot: Snapshot): Snapshot {
        if (snapshot.origin == Origin.QUALCOMM || !UcsiDebugfs.present(sysfs)) {
            return snapshot
        }

        /*
         * Port by port, because the policy manager answers per connector and a
         * board with two of them has two different cables to describe. A port
         * with nothing attached is not asked about at all.
         */
        val ports = snapshot.ports.map { port ->
            /*
             * Only a port with a contract has anything to ask about, and asking
             * anyway is not harmless: the tablet this was written against
             * answers GET_PDOS for a connector with nothing on it by handing
             * back the other connector's objects.
             */
            if (!port.attached || !port.inPowerDelivery()) {
                return@map port
            }

            val connector = port.connector ?: DEFAULT_CONNECTOR

            /*
             * Whatever the class managed, else ask. Asking is a command to the
             * policy manager, so it is not done where reading a directory would
             * have answered.
             */
            val capabilities = port.capabilities
                .ifEmpty { UcsiDebugfs.capabilities(sysfs, connector) }
            if (capabilities.isEmpty()) {
                return@map port
            }

            /* Reading the request needs the capability it was made against. */
            val request = UcsiDebugfs.request(sysfs, connector, capabilities)

            port.copy(capabilities = capabilities, request = request ?: port.request)
        }

        return snapshot.copy(
            ports = ports,
            emptyReason = if (ports.any { it.capabilities.isNotEmpty() }) {
                null
            } else {
                snapshot.emptyReason
            },
        )
    }

    /* One connector, where the type-C class did not say which. */
    private const val DEFAULT_CONNECTOR = 1

    /**
     * The way in to use. Reading the files directly is right when the device
     * tree labelled them for this app, which is the case in a ROM build and
     * generally not otherwise; a root shell is the fallback rather than the
     * default, because it costs a process per batch and may prompt.
     *
     * Presence, not a full read, decides: if the directories can be seen
     * without help then the reads will work too, and if they cannot then no
     * amount of trying changes it.
     */
    fun sysfs(preferRoot: Boolean = false): Sysfs {
        if (!preferRoot &&
            candidates().any { it.publishesCapabilities && it.present(DirectSysfs) }
        ) {
            return DirectSysfs
        }
        return if (RootSysfs.available()) RootSysfs else DirectSysfs
    }
}
