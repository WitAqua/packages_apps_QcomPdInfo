/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.source

import org.witaqua.qcom.pd_info.io.Sysfs

/*
 * Qualcomm's charger firmware, through the class that
 * drivers/power/supply/qti_battery_charger.c registers for it:
 *
 *   /sys/class/qcom-battery/usb_real_type
 *
 * That driver is the charger on every pmic-glink platform, which is all of them
 * from 5.10 on, and this attribute is the one thing it says that UCSI does not:
 * which kind of contract is in force. ucsi_psy_get_usb_type() answers PD for
 * every power delivery contract, PPS included, and the type-C class only ever
 * says usb_power_delivery - so on a board with no object list there is
 * otherwise no way to tell a fixed contract from a programmable one. That is
 * exactly what decides whether UCSI's own voltage and current mean anything, so
 * it is worth one read.
 *
 * The values are the charger's own list, which is the power supply class's with
 * qualcomm's additions on the end: Unknown, SDP, DCP, CDP, ACA, C, PD, PD_DRP,
 * PD_PPS, BrickID, USB_FLOAT, HVDCP, HVDCP_3, HVDCP_3P5.
 */
internal object QtiCharger {
    private const val REAL_TYPE = "/sys/class/qcom-battery/usb_real_type"

    /** What the charger calls the adapter, or null where it will not say. */
    fun realType(sysfs: Sysfs): String? =
        sysfs.read(REAL_TYPE)?.takeIf { it != UNKNOWN }

    /**
     * Whether the contract is against a programmable supply. Null where the
     * platform does not publish the type at all, which is a different answer
     * from "no" and is treated as one.
     *
     * PD_DRP is a dual-role fixed contract rather than a third kind, so it and
     * everything else that is not PPS reads correctly out of UCSI: the non-PD
     * types come from the driver's own constants rather than from an object.
     */
    fun programmable(realType: String?): Boolean? =
        when (realType) {
            null -> null
            PROGRAMMABLE -> true
            else -> false
        }

    private const val UNKNOWN = "Unknown"
    private const val PROGRAMMABLE = "PD_PPS"
}
