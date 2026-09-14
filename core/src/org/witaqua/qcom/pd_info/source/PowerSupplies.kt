/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.source

import org.witaqua.qcom.pd_info.io.Sysfs
import org.witaqua.qcom.pd_info.model.Measured
import org.witaqua.qcom.pd_info.model.Port

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

    /**
     * The supply belonging to one connector. There is one per connector and the
     * name ends in its number, so on a board with two ports taking whichever
     * sorts first means reporting the wrong port's contract - which on the
     * tablet this was written against is a data cable's 5V where the charger
     * had negotiated 9V. Asked without a connector, or where the board has only
     * one, the single answer is the right one.
     */
    fun name(sysfs: Sysfs, connector: Int? = null): String? {
        val supplies = sysfs.list(POWER_SUPPLY).filter { it.startsWith(PREFIX) }
        if (connector == null || supplies.size < 2) {
            return supplies.firstOrNull()
        }
        return supplies.firstOrNull { number(it) == connector }
    }

    /** The trailing digits of the name, which are the connector it is for. */
    private fun number(supply: String) = supply.takeLastWhile { it.isDigit() }.toIntOrNull()

    /** Whether a supply belongs to UCSI, so the charger's reader can skip it. */
    fun owns(supply: String) = supply.startsWith(PREFIX)

    /** What this supply says about one connector's contract. */
    fun contract(sysfs: Sysfs, connector: Int? = null): Contract {
        val directory = "$POWER_SUPPLY/${name(sysfs, connector) ?: return Contract()}"

        val values = sysfs.read(
            listOf("online", "usb_type", "voltage_now", "current_now").map { "$directory/$it" }
        )
        if (values["$directory/online"] != "1") {
            return Contract()
        }

        return Contract(
            online = true,
            protocol = values["$directory/usb_type"]?.activeValue(),
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
 * What the platform says the contract is: UCSI's figures for it, and where
 * [withCharger] could be applied, the charger's name for what is on the other
 * end. Absent throughout when there is no contract, and in whichever part the
 * board does not publish.
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

/**
 * Whether power delivery is what is in force on this port. UCSI's supply
 * answers for a port either way - against a plain type-C cable it reads back
 * the standard's own 5V and no current - so without asking, a cable to a
 * laptop would be reported as a negotiation. The class says it one way and the
 * supply the other.
 */
internal fun Port.inPowerDelivery(): Boolean =
    contract == PD_OPERATION_MODE || protocol?.startsWith("PD") == true

private const val PD_OPERATION_MODE = "usb_power_delivery"

/**
 * The charger firmware's word for the adapter, laid over what UCSI said - but
 * only on a board with one port. That reading is a single view of "the"
 * charger with no connector in it, so where there are two ports there is
 * nothing to say which one it is about: the tablet this was written against
 * reports SDP for the data cable in one port while the other holds a 9V
 * contract. Better to leave the kind of contract unknown than to attach it to
 * the wrong port. See [QtiCharger].
 */
internal fun Contract.withCharger(sysfs: Sysfs, ports: Int): Contract {
    if (ports != 1) {
        return this
    }

    val real = QtiCharger.realType(sysfs) ?: return this
    return copy(protocol = real, programmable = QtiCharger.programmable(real))
}

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
