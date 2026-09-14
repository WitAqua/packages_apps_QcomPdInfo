/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.source

import org.witaqua.qcom.pd_info.io.Sysfs
import org.witaqua.qcom.pd_info.model.Port

/*
 * The type-C class, drivers/usb/typec/class.c, which every port driver
 * registers with whatever else it publishes. It carries the roles and the
 * power operation mode and no data objects at all, so it is never a source on
 * its own - it is the half of a port that the interfaces below agree on, read
 * in one place rather than in each of them.
 */
internal object TypeCClass {
    const val DIRECTORY = "/sys/class/typec"
    const val PARTNER_SUFFIX = "-partner"

    /**
     * The ports, without the partner and cable devices that sit beside them in
     * the same class: those all carry a dash and a port never does.
     */
    fun ports(sysfs: Sysfs): List<String> = sysfs.list(DIRECTORY).filterNot { it.contains('-') }

    /**
     * The UCSI connector a port is. The class numbers ports from zero and UCSI
     * numbers connectors from one, and the difference matters twice over: it
     * names the port's power supply, and it is what a command to the policy
     * manager has to carry. Null for a port whose name does not follow the
     * class's own convention, since nothing better can be guessed.
     */
    fun connector(port: String): Int? = port.removePrefix("port").toIntOrNull()?.plus(1)

    /**
     * The power delivery device holding what the partner advertised, where
     * there is one. This symlink is the only thread between the two: UCSI
     * registers a partner's device as a virtual one, which carries no link back
     * to the port, so a board with two ports cannot otherwise be told which
     * list belongs to which.
     */
    fun partnerDevice(sysfs: Sysfs, port: String): String? =
        sysfs.resolve("$DIRECTORY/$port$PARTNER_SUFFIX/usb_power_delivery")

    /**
     * What the class says about one port. Anything a data object would add is
     * left for the caller to fill in.
     */
    fun port(sysfs: Sysfs, name: String): Port {
        val directory = "$DIRECTORY/$name"
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
            /*
             * A partner device is what the class grows when something is
             * plugged in and drops when it comes out, which is the plainest
             * signal for that there is.
             */
            attached = sysfs.list(DIRECTORY).contains("$name$PARTNER_SUFFIX"),
            name = name,
            powerRole = values["$directory/power_role"]?.activeValue(),
            dataRole = values["$directory/data_role"]?.activeValue(),
            contract = values["$directory/power_operation_mode"],
            pdRevision = values["$directory/usb_power_delivery_revision"],
            partnerSupportsPd = values["$partner/supports_usb_power_delivery"]?.equals("yes"),
            connector = connector(name),
        )
    }
}
