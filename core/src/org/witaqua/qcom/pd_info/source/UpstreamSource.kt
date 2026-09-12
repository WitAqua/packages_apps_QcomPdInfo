/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.source

import org.witaqua.qcom.pd_info.io.Sysfs
import org.witaqua.qcom.pd_info.model.EmptyReason
import org.witaqua.qcom.pd_info.model.Measured
import org.witaqua.qcom.pd_info.model.Origin
import org.witaqua.qcom.pd_info.model.Port
import org.witaqua.qcom.pd_info.model.Snapshot
import org.witaqua.qcom.pd_info.model.SourceCapability
import org.witaqua.qcom.pd_info.model.SourceFlags

/*
 * The upstream interface, drivers/usb/typec/pd.c, from Linux 5.18. Each data
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
    private const val TYPEC = "/sys/class/typec"
    private const val SUPPLY = "/sys/class/power_supply"

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
        } ?: sysfs.list(TYPEC)
            .filter { it.endsWith(PARTNER_SUFFIX) }
            .firstNotNullOfOrNull { partner ->
                sysfs.read("$TYPEC/$partner/usb_power_delivery")?.substringAfterLast('/')
            }

        val capabilities = partnerDevice?.let { capabilities(sysfs, it) } ?: emptyList()

        /*
         * The contract, by way of UCSI's own power supply. Read here rather
         * than per port so that it survives the type-C class being unreadable:
         * it is the only place the negotiated current appears at all.
         */
        val contract = ucsiContract(sysfs)

        val names = sysfs.list(TYPEC).filterNot { it.contains('-') }
        val ports = if (names.isNotEmpty()) {
            names.map { port(sysfs, it, capabilities, contract) }
        } else {
            /* No type-C class to read, so report the port without naming it. */
            listOf(
                Port(
                    name = null,
                    capabilities = capabilities,
                    protocol = contract.protocol,
                    negotiatedMilliamps = contract.milliamps,
                )
            )
        }

        return Snapshot(
            origin = Origin.UPSTREAM,
            ports = ports,
            measured = measured(sysfs),
            emptyReason = if (capabilities.isEmpty()) {
                EmptyReason.NO_CAPABILITIES_REGISTERED
            } else {
                null
            },
        )
    }

    /**
     * What UCSI's power supply says about the contract. Its current is derived
     * from the request object - rdo_op_current() - so it is an agreed figure
     * and not a measurement; its voltage needs the source object the request
     * points at, so on a platform that never read the objects it reads zero
     * and is left alone here.
     */
    private fun ucsiContract(sysfs: Sysfs): Contract {
        val supply = sysfs.list(SUPPLY).firstOrNull { it.startsWith(UCSI_SUPPLY_PREFIX) }
            ?: return Contract()
        val directory = "$SUPPLY/$supply"

        val values = sysfs.read(
            listOf("online", "usb_type", "current_now").map { "$directory/$it" }
        )
        if (values["$directory/online"] != "1") {
            return Contract()
        }

        return Contract(
            protocol = values["$directory/usb_type"]?.activeValue(),
            milliamps = values["$directory/current_now"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let { it / 1000 },
        )
    }

    private data class Contract(val protocol: String? = null, val milliamps: Int? = null)

    private fun port(
        sysfs: Sysfs,
        name: String,
        capabilities: List<SourceCapability>,
        contract: Contract,
    ): Port {
        val directory = "$TYPEC/$name"
        val partner = "$directory$PARTNER_SUFFIX"

        val values = sysfs.read(
            listOf(
                "$directory/power_role",
                "$directory/data_role",
                "$directory/power_operation_mode",
                "$directory/usb_power_delivery_revision",
                "$partner/supports_usb_power_delivery",
            )
        )

        return Port(
            name = name,
            powerRole = values["$directory/power_role"]?.activeValue(),
            dataRole = values["$directory/data_role"]?.activeValue(),
            contract = values["$directory/power_operation_mode"],
            pdRevision = values["$directory/usb_power_delivery_revision"],
            partnerSupportsPd = values["$partner/supports_usb_power_delivery"]?.equals("yes"),
            capabilities = capabilities,
            negotiatedMilliamps = contract.milliamps,
            protocol = contract.protocol,
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

    /*
     * The vendor's charger supply, which measures the port rather than saying
     * what was agreed. Taken as whichever USB-typed supply is online and has
     * numbers, skipping UCSI's own - that one reports the contract, not a
     * measurement, and is read above.
     */
    private fun measured(sysfs: Sysfs): Measured? =
        sysfs.list(SUPPLY)
            .filterNot { it.startsWith(UCSI_SUPPLY_PREFIX) }
            .firstNotNullOfOrNull { name ->
                val directory = "$SUPPLY/$name"
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

    private const val PARTNER_SUFFIX = "-partner"
    private const val UCSI_SUPPLY_PREFIX = "ucsi-source-psy"

    private val FIXED_FLAGS = listOf(
        "unconstrained_power",
        "dual_role_power",
        "dual_role_data",
        "usb_communication_capable",
        "usb_suspend_supported",
    )
}
