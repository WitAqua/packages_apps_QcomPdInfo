/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.source

import org.witaqua.qcom.pd_info.io.Sysfs
import org.witaqua.qcom.pd_info.model.Measured

/** Both readers below live in the power supply class. */
internal const val POWER_SUPPLY = "/sys/class/power_supply"

/*
 * UCSI's own power supply, drivers/usb/typec/ucsi/psy.c, which is registered
 * per connector wherever the driver is built with CONFIG_POWER_SUPPLY - so it
 * is there on every UCSI platform, including the ones with no interface that
 * publishes a data object at all.
 *
 * What it carries is the contract rather than a measurement: the current is
 * rdo_op_current(con->rdo) and the voltage is the fixed voltage of the source
 * object that request names. Both of those read the fixed-supply fields of
 * whatever word they are handed, so against a programmable supply they land on
 * the wrong bits - see [UcsiSupplySource], which says so on the screen.
 */
internal object UcsiSupply {
    /** ucsi-source-psy-<parent device><connector>, one per connector. */
    private const val PREFIX = "ucsi-source-psy"

    fun name(sysfs: Sysfs): String? =
        sysfs.list(POWER_SUPPLY).firstOrNull { it.startsWith(PREFIX) }

    /** Whether a supply belongs to UCSI, so the charger's reader can skip it. */
    fun owns(supply: String) = supply.startsWith(PREFIX)

    /**
     * What can be said about the contract, which on a qualcomm board is two
     * readers' worth: the charger firmware names the adapter and this supply
     * carries the figures. Either can be absent - the charger's class is not
     * on a board from anyone else, and the supply is not there without UCSI -
     * so the answer is whatever the two of them managed.
     */
    fun contract(sysfs: Sysfs): Contract {
        /*
         * The charger's word for the adapter first, because UCSI's is always
         * just PD and this one separates PPS from fixed - see [QtiCharger].
         */
        val real = QtiCharger.realType(sysfs)
        val charger = Contract(
            protocol = real,
            programmable = QtiCharger.programmable(real),
        )

        val directory = "$POWER_SUPPLY/${name(sysfs) ?: return charger}"

        val values = sysfs.read(
            listOf("online", "usb_type", "voltage_now", "current_now").map { "$directory/$it" }
        )
        if (values["$directory/online"] != "1") {
            return charger
        }

        return charger.copy(
            online = true,
            protocol = real ?: values["$directory/usb_type"]?.activeValue(),
            millivolts = values["$directory/voltage_now"].milli(),
            milliamps = values["$directory/current_now"].milli(),
        )
    }

    /*
     * Micro units, as the power supply class quotes everything. A zero is the
     * driver saying it has nothing rather than a reading of nothing: with no
     * source objects to index, both of these come out as exactly that.
     */
    private fun String?.milli(): Int? =
        this?.toIntOrNull()?.takeIf { it > 0 }?.let { it / 1000 }
}

/**
 * What the platform says the contract is: UCSI's figures for it, and the
 * charger's name for what is on the other end. Absent throughout when there is
 * no contract, and in whichever part the board does not publish.
 */
internal data class Contract(
    val online: Boolean = false,
    val protocol: String? = null,

    /**
     * Whether the contract is against a programmable supply, where the charger
     * firmware says so, and null where nothing on the board does. It decides
     * whether the two figures below can be believed.
     */
    val programmable: Boolean? = null,

    val millivolts: Int? = null,
    val milliamps: Int? = null,
)

/*
 * The vendor's charger supply, which measures the port rather than saying what
 * was agreed. UCSI's own is skipped: that one reports the contract, and is
 * read above.
 */
internal object ChargerSupply {
    fun measured(sysfs: Sysfs): Measured? =
        sysfs.list(POWER_SUPPLY)
            .filterNot { UcsiSupply.owns(it) }
            .firstNotNullOfOrNull { name ->
                val directory = "$POWER_SUPPLY/$name"
                val values = sysfs.read(
                    listOf("type", "online", "voltage_now", "current_now")
                        .map { "$directory/$it" }
                )

                val type = values["$directory/type"] ?: return@firstNotNullOfOrNull null
                if (!type.startsWith("USB") || values["$directory/online"] != "1") {
                    return@firstNotNullOfOrNull null
                }

                val millivolts = values["$directory/voltage_now"]?.toIntOrNull()?.let { it / 1000 }
                val milliamps = values["$directory/current_now"]?.toIntOrNull()?.let { it / 1000 }
                if (millivolts == null && milliamps == null) {
                    return@firstNotNullOfOrNull null
                }

                Measured(name, type, millivolts, milliamps)
            }
}
