/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.source

import org.witaqua.qcom.pd_info.io.Sysfs
import org.witaqua.qcom.pd_info.model.EmptyReason
import org.witaqua.qcom.pd_info.model.Origin
import org.witaqua.qcom.pd_info.model.Port
import org.witaqua.qcom.pd_info.model.Snapshot
import org.witaqua.qcom.pd_info.model.SourceCapability
import org.witaqua.qcom.pd_info.model.SourceFlags

/*
 * The upstream interface, drivers/usb/typec/pd.c, from android14-6.1. Each data
 * object is a directory of decimal fields rather than one word, so there is
 * nothing to decode here - only to read in the right order.
 *
 * Two things it does not have. There is no request object anywhere in the
 * class, so what was actually taken has to come from elsewhere; and the
 * capabilities are only registered when the driver read them, which with UCSI
 * means the firmware reporting PDO details. A platform that does not leaves the
 * devices in place and empty, which is the interesting case rather than an
 * error - see [EmptyReason].
 */
object UpstreamSource : PdSource {
    private const val PD = "/sys/class/usb_power_delivery"

    override fun present(sysfs: Sysfs) = sysfs.list(PD).isNotEmpty()

    override fun read(sysfs: Sysfs): Snapshot? {
        val devices = sysfs.list(PD)
        if (devices.isEmpty()) {
            return null
        }

        val names = TypeCClass.ports(sysfs)
        val ports = if (names.isNotEmpty()) {
            names.map { name -> port(sysfs, name, names.size) }
        } else {
            /*
             * No type-C class to read - on at least one Android 16 build the
             * shell is refused it while the power delivery class is allowed -
             * so report the port without naming it, and take the objects from
             * whichever device turns out to hold them.
             */
            val capabilities = unattributed(sysfs, devices)
            val contract = UcsiSupply.contract(sysfs).withCharger(sysfs, ports = 1)

            listOf(
                Port(
                    attached = capabilities.isNotEmpty(),
                    name = null,
                    capabilities = capabilities,
                    protocol = contract.protocol,
                    negotiatedMilliamps = contract.milliamps,
                )
            )
        }

        /*
         * A partner device is what the class grows when something is plugged
         * in and drops when it comes out, so its absence is the difference
         * between "nothing attached" and "attached, and the objects were never
         * read" - which from the capabilities alone look the same.
         */
        return Snapshot(
            origin = Origin.UPSTREAM,
            ports = ports,
            measured = ChargerSupply.measured(sysfs),
            emptyReason = when {
                ports.any { it.capabilities.isNotEmpty() } -> null
                ports.none { it.attached } -> EmptyReason.NOTHING_ATTACHED
                else -> EmptyReason.NO_CAPABILITIES_REGISTERED
            },
        )
    }

    /**
     * One port, with the objects its own partner advertised and the contract
     * its own connector negotiated. Everything here is per port on purpose: a
     * board with two of them can hold a charger on one and a data cable on the
     * other, and sharing either would describe the wrong cable.
     */
    private fun port(sysfs: Sysfs, name: String, ports: Int): Port {
        val connector = TypeCClass.connector(name)
        val contract = UcsiSupply.contract(sysfs, connector).withCharger(sysfs, ports)
        val partner = TypeCClass.partnerDevice(sysfs, name)

        val capabilities = partner?.let { capabilities(sysfs, it, Role.SOURCE) } ?: emptyList()

        val port = TypeCClass.port(sysfs, name).copy(
            capabilities = capabilities,
            /*
             * What this port asks for, which is its own rather than the
             * cable's: on a board with two of them they need not match, and it
             * is the one thing the class says about a port with nothing in it.
             */
            sinkCapabilities = TypeCClass.portDevice(sysfs, name)
                ?.let { capabilities(sysfs, it, Role.SINK) }
                ?: emptyList(),
            protocol = contract.protocol,
            /*
             * A menu with no programmable supply on it cannot have been
             * ordered from as one, which is worth knowing where the charger
             * firmware does not say: it is what decides whether UCSI's two
             * figures below read from the right fields. The other way round
             * proves nothing - an offered PPS object is not a PPS contract.
             */
            programmable = contract.programmable
                ?: false.takeIf {
                    capabilities.isNotEmpty() &&
                        capabilities.none { it is SourceCapability.Programmable }
                },
        )
        if (!port.inPowerDelivery()) {
            return port
        }

        /*
         * UCSI works both figures out of the request object, so they are worth
         * having even here where the objects themselves were read: the class
         * publishes no request, and without one there is nothing else to say
         * which line of the menu was ordered.
         */
        return port.copy(
            negotiatedMillivolts = contract.millivolts.takeUnless { port.programmable == true },
            negotiatedMilliamps = contract.milliamps.takeUnless { port.programmable == true },
        )
    }

    /**
     * The objects where there is no type-C class to say whose they are. A
     * partner is the only owner that carries supports_usb_power_delivery, and
     * failing that the only device with a list is the one worth reading.
     */
    private fun unattributed(sysfs: Sysfs, devices: List<String>): List<SourceCapability> {
        val owner = devices.firstOrNull { name ->
            sysfs.read("$PD/$name/device/supports_usb_power_delivery") != null
        } ?: devices.firstOrNull { name ->
            sysfs.list("$PD/$name/source-capabilities").isNotEmpty()
        }

        return owner?.let { capabilities(sysfs, it, Role.SOURCE) } ?: emptyList()
    }

    /*
     * Which way round the objects are read. They are the same objects either
     * way; what differs is the directory they sit in and whether the current a
     * fixed or variable supply quotes is the most it can give or the amount the
     * other end means to draw.
     */
    private enum class Role(
        val directory: String,
        val current: String,
        val power: String,
    ) {
        SOURCE("source-capabilities", "maximum_current", "maximum_power"),
        SINK("sink-capabilities", "operational_current", "operational_power"),
    }

    private fun capabilities(
        sysfs: Sysfs,
        device: String,
        role: Role,
    ): List<SourceCapability> {
        val directory = "$PD/$device/${role.directory}"

        return sysfs.list(directory).mapNotNull { entry ->
            /* Named "<position>:<type>", which is the whole of the ordering. */
            val position = entry.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            val objectDirectory = "$directory/$entry"

            when (entry.substringAfter(':')) {
                "fixed_supply" -> fixed(sysfs, objectDirectory, position, role)
                "battery" -> battery(sysfs, objectDirectory, position, role)
                "variable_supply" -> variable(sysfs, objectDirectory, position, role)
                "programmable_supply" -> programmable(sysfs, objectDirectory, position)
                "spr_adjustable_voltage_supply" -> adjustable(sysfs, objectDirectory, position)
                else -> null
            }
        }.sortedBy { it.position }
    }

    private fun fixed(
        sysfs: Sysfs,
        directory: String,
        position: Int,
        role: Role,
    ): SourceCapability? {
        val values = sysfs.read(
            FIXED_FLAGS.map { "$directory/$it" } +
                listOf("$directory/voltage", "$directory/${role.current}")
        )
        val millivolts = values["$directory/voltage"].quantity() ?: return null

        /*
         * The kernel only exposes the source-wide bits on object 1, the same
         * place the specification puts them, so their presence is the test.
         */
        val flags = if (FIXED_FLAGS.any { values.containsKey("$directory/$it") }) {
            SourceFlags(
                dualRolePower = values["$directory/dual_role_power"] == "1",
                suspendSupported = values["$directory/usb_suspend_supported"] == "1",
                unconstrainedPower = values["$directory/unconstrained_power"] == "1",
                usbCommunicationsCapable =
                    values["$directory/usb_communication_capable"] == "1",
                dualRoleData = values["$directory/dual_role_data"] == "1",
            )
        } else {
            null
        }

        return SourceCapability.Fixed(
            position = position,
            millivolts = millivolts,
            maxMilliamps = values["$directory/${role.current}"].quantity() ?: 0,
            flags = flags,
        )
    }

    private fun battery(
        sysfs: Sysfs,
        directory: String,
        position: Int,
        role: Role,
    ): SourceCapability? {
        val values = sysfs.read(
            listOf("minimum_voltage", "maximum_voltage", role.power).map { "$directory/$it" }
        )
        return SourceCapability.Battery(
            position = position,
            minMillivolts = values["$directory/minimum_voltage"].quantity() ?: return null,
            maxMillivolts = values["$directory/maximum_voltage"].quantity() ?: return null,
            maxMilliwatts = values["$directory/${role.power}"].quantity() ?: 0,
        )
    }

    private fun variable(
        sysfs: Sysfs,
        directory: String,
        position: Int,
        role: Role,
    ): SourceCapability? {
        val values = sysfs.read(
            listOf("minimum_voltage", "maximum_voltage", role.current).map { "$directory/$it" }
        )
        return SourceCapability.Variable(
            position = position,
            minMillivolts = values["$directory/minimum_voltage"].quantity() ?: return null,
            maxMillivolts = values["$directory/maximum_voltage"].quantity() ?: return null,
            maxMilliamps = values["$directory/${role.current}"].quantity() ?: 0,
        )
    }

    private fun programmable(sysfs: Sysfs, directory: String, position: Int): SourceCapability? {
        val values = sysfs.read(
            listOf("minimum_voltage", "maximum_voltage", "maximum_current", "pps_power_limited")
                .map { "$directory/$it" }
        )
        return SourceCapability.Programmable(
            position = position,
            minMillivolts = values["$directory/minimum_voltage"].quantity() ?: return null,
            maxMillivolts = values["$directory/maximum_voltage"].quantity() ?: return null,
            maxMilliamps = values["$directory/maximum_current"].quantity() ?: 0,
            powerLimited = values["$directory/pps_power_limited"] == "1",
        )
    }

    private fun adjustable(sysfs: Sysfs, directory: String, position: Int): SourceCapability? {
        val values = sysfs.read(
            listOf("maximum_current_9V_to_15V", "maximum_current_15V_to_20V")
                .map { "$directory/$it" }
        )
        return SourceCapability.Adjustable(
            position = position,
            milliampsAt9To15V = values["$directory/maximum_current_9V_to_15V"].quantity()
                ?: return null,
            milliampsAt15To20V = values["$directory/maximum_current_15V_to_20V"].quantity()
                ?: 0,
        )
    }

    /**
     * One of the class's numbers. Every quantity it publishes carries its unit
     * - drivers/usb/typec/pd.c prints "5000mV", "3000mA", "15000mW" - so the
     * digits are the whole of the value and anything else is the unit saying
     * which field it was.
     */
    private fun String?.quantity(): Int? = this?.takeWhile { it.isDigit() }?.toIntOrNull()

    private val FIXED_FLAGS = listOf(
        "unconstrained_power",
        "dual_role_power",
        "dual_role_data",
        "usb_communication_capable",
        "usb_suspend_supported",
    )
}
