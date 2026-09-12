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
import org.witaqua.qcom.pd_info.model.EmptyReason
import org.witaqua.qcom.pd_info.model.Snapshot

/**
 * Picking where to read from.
 *
 * The platform and the kernel version decide the order to try in, because they
 * say which interface a board plausibly has: the upstream class arrived in
 * 5.18, and the driver that publishes the 32-bit objects is qualcomm's, so a
 * 5.4 qualcomm board can only have the latter and a 6.x board from anyone else
 * can only have the former.
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
     * Fills in what the class left out, by asking the policy manager directly.
     *
     * Only reached when the interface is there and empty, which is a platform
     * whose firmware does not report that it can list its objects - and which
     * may well answer if asked anyway. Everything else is left alone: a source
     * that answered properly has nothing to add, and putting commands to the
     * policy manager is not something to do speculatively.
     */
    private fun fillIn(sysfs: Sysfs, snapshot: Snapshot): Snapshot {
        if (snapshot.emptyReason != EmptyReason.NO_CAPABILITIES_REGISTERED) {
            return snapshot
        }
        if (!UcsiDebugfs.present(sysfs)) {
            return snapshot
        }

        val capabilities = UcsiDebugfs.capabilities(sysfs)
        if (capabilities.isEmpty()) {
            return snapshot
        }

        val request = UcsiDebugfs.request(sysfs, capabilities)

        return snapshot.copy(
            ports = snapshot.ports.map {
                it.copy(capabilities = capabilities, request = request ?: it.request)
            },
            emptyReason = null,
        )
    }

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
        if (!preferRoot && candidates().any { it.present(DirectSysfs) }) {
            return DirectSysfs
        }
        return if (RootSysfs.available()) RootSysfs else DirectSysfs
    }
}
