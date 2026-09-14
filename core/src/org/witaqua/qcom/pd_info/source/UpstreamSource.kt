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

        /*
         * Which power delivery device is the charger's. Decided from the device
         * behind it rather than from the type-C symlink that points at it,
         * because the type-C class is not always readable - on at least one
         * Android 16 build the shell is refused it while the power delivery
         * class is allowed - and a partner is the only owner that carries
         * supports_usb_power_delivery.
         */
        val partnerDevice = devices.firstOrNull { name ->
            sysfs.read("$PD/$name/device/supports_usb_power_delivery") != null
        } ?: sysfs.list(TypeCClass.DIRECTORY)
            .filter { it.endsWith(TypeCClass.PARTNER_SUFFIX) }
            .firstNotNullOfOrNull { partner ->
                sysfs.read("${TypeCClass.DIRECTORY}/$partner/usb_power_delivery")
                    ?.substringAfterLast('/')
            }

        val capabilities = partnerDevice?.let { capabilities(sysfs, it) } ?: emptyList()

        /*
         * The contract, by way of UCSI's own power supply. Read here rather
         * than per port so that it survives the type-C class being unreadable:
         * it is the only place the negotiated current appears at all.
         */
        val contract = UcsiSupply.contract(sysfs)

        val names = TypeCClass.ports(sysfs)
        val ports = if (names.isNotEmpty()) {
            names.map { name ->
                TypeCClass.port(sysfs, name).copy(
                    capabilities = capabilities,
                    protocol = contract.protocol,
                    negotiatedMilliamps = contract.milliamps,
                )
            }
        } else {
            /* No type-C class to read, so report the port without naming it. */
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
        val attached = ports.any { it.attached }

        return Snapshot(
            origin = Origin.UPSTREAM,
            ports = ports,
            measured = ChargerSupply.measured(sysfs),
            emptyReason = when {
                capabilities.isNotEmpty() -> null
                !attached -> EmptyReason.NOTHING_ATTACHED
                else -> EmptyReason.NO_CAPABILITIES_REGISTERED
            },
        )
    }

    private fun capabilities(sysfs: Sysfs, device: String): List<SourceCapability> {
        val directory = "$PD/$device/source-capabilities"

        return sysfs.list(directory).mapNotNull { entry ->
            /* Named "<position>:<type>", which is the whole of the ordering. */
            val position = entry.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            val objectDirectory = "$directory/$entry"

            when (entry.substringAfter(':')) {
                "fixed_supply" -> fixed(sysfs, objectDirectory, position)
                "battery" -> battery(sysfs, objectDirectory, position)
                "variable_supply" -> variable(sysfs, objectDirectory, position)
                "programmable_supply" -> programmable(sysfs, objectDirectory, position)
                "spr_adjustable_voltage_supply" -> adjustable(sysfs, objectDirectory, position)
                else -> null
            }
        }.sortedBy { it.position }
    }

    private fun fixed(sysfs: Sysfs, directory: String, position: Int): SourceCapability? {
        val values = sysfs.read(
            FIXED_FLAGS.map { "$directory/$it" } +
                listOf("$directory/voltage", "$directory/maximum_current")
        )
        val millivolts = values["$directory/voltage"]?.toIntOrNull() ?: return null

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
            maxMilliamps = values["$directory/maximum_current"]?.toIntOrNull() ?: 0,
            flags = flags,
        )
    }

    private fun battery(sysfs: Sysfs, directory: String, position: Int): SourceCapability? {
        val values = sysfs.read(
            listOf("minimum_voltage", "maximum_voltage", "maximum_power").map { "$directory/$it" }
        )
        return SourceCapability.Battery(
            position = position,
            minMillivolts = values["$directory/minimum_voltage"]?.toIntOrNull() ?: return null,
            maxMillivolts = values["$directory/maximum_voltage"]?.toIntOrNull() ?: return null,
            maxMilliwatts = values["$directory/maximum_power"]?.toIntOrNull() ?: 0,
        )
    }

    private fun variable(sysfs: Sysfs, directory: String, position: Int): SourceCapability? {
        val values = sysfs.read(
            listOf("minimum_voltage", "maximum_voltage", "maximum_current").map { "$directory/$it" }
        )
        return SourceCapability.Variable(
            position = position,
            minMillivolts = values["$directory/minimum_voltage"]?.toIntOrNull() ?: return null,
            maxMillivolts = values["$directory/maximum_voltage"]?.toIntOrNull() ?: return null,
            maxMilliamps = values["$directory/maximum_current"]?.toIntOrNull() ?: 0,
        )
    }

    private fun programmable(sysfs: Sysfs, directory: String, position: Int): SourceCapability? {
        val values = sysfs.read(
            listOf("minimum_voltage", "maximum_voltage", "maximum_current", "pps_power_limited")
                .map { "$directory/$it" }
        )
        return SourceCapability.Programmable(
            position = position,
            minMillivolts = values["$directory/minimum_voltage"]?.toIntOrNull() ?: return null,
            maxMillivolts = values["$directory/maximum_voltage"]?.toIntOrNull() ?: return null,
            maxMilliamps = values["$directory/maximum_current"]?.toIntOrNull() ?: 0,
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
            milliampsAt9To15V = values["$directory/maximum_current_9V_to_15V"]?.toIntOrNull()
                ?: return null,
            milliampsAt15To20V = values["$directory/maximum_current_15V_to_20V"]?.toIntOrNull()
                ?: 0,
        )
    }

    private val FIXED_FLAGS = listOf(
        "unconstrained_power",
        "dual_role_power",
        "dual_role_data",
        "usb_communication_capable",
        "usb_suspend_supported",
    )
}
