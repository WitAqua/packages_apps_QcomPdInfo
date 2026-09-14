/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info

import android.os.Build
import android.system.Os

/**
 * What this handset is, to the extent that it decides where the power delivery
 * state is published. Two things matter, and neither is conclusive on its own:
 *
 *  - the kernel version, because the upstream class arrived in android14-6.1
 *    and the qualcomm driver is what came before it;
 *  - the platform, because the qualcomm driver is qualcomm's and a board from
 *    anyone else will not have it whatever its kernel.
 *
 * Neither settles it. A 6.12 board can register the upstream devices and put
 * nothing in them, and no version says so - see [org.witaqua.qcom.pd_info
 * .source.Sources] for what is done about that.
 */
object Platform {
    /** Kernel release, as uname reports it: "6.12.23-android16-5-g16e473..." */
    val kernelRelease: String by lazy {
        try {
            Os.uname().release ?: ""
        } catch (e: Exception) {
            System.getProperty("os.version") ?: ""
        }
    }

    /** The leading major.minor of the above, or null when it will not parse. */
    val kernelVersion: KernelVersion? by lazy { KernelVersion.parse(kernelRelease) }

    /**
     * The SoC, preferring the board platform the vendor image sets - "kalama",
     * "sm8650" - and falling back to what Build exposes. Lower case throughout.
     */
    val platform: String by lazy {
        sequenceOf(
            Build.SOC_MODEL.takeIf { it != Build.UNKNOWN },
            Build.BOARD.takeIf { it != Build.UNKNOWN },
            Build.HARDWARE,
        ).filterNotNull().firstOrNull { it.isNotBlank() }?.lowercase() ?: ""
    }

    val manufacturer: String by lazy { Build.SOC_MANUFACTURER.lowercase() }

    /**
     * Whether this looks like a qualcomm part. The driver that publishes the
     * 32-bit objects is theirs, so on anything else there is no point looking
     * for it. Deliberately generous: being wrong here only costs one stat().
     */
    val looksQualcomm: Boolean by lazy {
        manufacturer.contains("qualcomm") ||
            /* What ro.soc.manufacturer actually says on their parts. */
            manufacturer == "qti" ||
            platform.startsWith("msm") ||
            platform.startsWith("sm") ||
            platform.startsWith("sdm") ||
            platform.startsWith("qcm") ||
            platform.startsWith("sc") ||
            QUALCOMM_CODENAMES.any { platform.contains(it) }
    }

    /*
     * Qualcomm's own names for the recent parts, which is what ro.board.platform
     * carries rather than the marketing number. Only the ones old enough to
     * still be worth checking for the legacy driver need be here; anything
     * newer is found by the probe rather than by name.
     */
    private val QUALCOMM_CODENAMES = listOf(
        "kona", "lito", "lahaina", "shima", "yupik", "taro", "diwali",
        "kalama", "pineapple", "sun", "canoe", "glymur", "kaanapali",
    )
}

data class KernelVersion(val major: Int, val minor: Int) : Comparable<KernelVersion> {
    override fun compareTo(other: KernelVersion): Int =
        if (major != other.major) major - other.major else minor - other.minor

    override fun toString() = "$major.$minor"

    companion object {
        private val LEADING = Regex("""^(\d+)\.(\d+)""")

        /**
         * The oldest android kernel carrying the upstream usb_power_delivery
         * class - drivers/usb/typec/pd.c is in android14-6.1 and not in
         * android13-5.15 - so anything older cannot have it however it is
         * configured.
         */
        val UPSTREAM_PD_CLASS = KernelVersion(6, 1)

        fun parse(release: String): KernelVersion? =
            LEADING.find(release)?.destructured?.let { (major, minor) ->
                KernelVersion(major.toInt(), minor.toInt())
            }
    }
}
