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

/*
 * The type-C class, UCSI's power supply and the charger firmware, which is what
 * is left on a board where nothing publishes a data object anywhere.
 *
 * That combination is not exotic. An android kernel older than 6.1 has no
 * usb_power_delivery class, one older than 6.6 has no UCSI debugfs, and on a
 * platform whose power delivery lives in the charger firmware qualcomm's own
 * driver cannot be built either - the PMIC carries no PD PHY for it to talk
 * to. The sm8450 parts on 5.10 are all three at once, which is what this was
 * written for: see docs/5.10_xiaomi-sm8450.md.
 *
 * The objects themselves are not missing. ucsi_pwr_opmode_change() keeps
 * con->src_pdos and con->rdo whenever a contract is in force; there is simply
 * nowhere to read them from, and what UCSI's power supply derives from them is
 * all userspace gets. So this reports the contract and says why the menu it
 * was chosen from is not there.
 *
 * The charger firmware is read beside it because it is the only thing that says
 * which kind of contract that is, and that is what decides whether UCSI's two
 * figures can be shown at all - see [QtiCharger].
 */
object UcsiSupplySource : PdSource {
    /*
     * Nothing here can produce an object list, which is what keeps the choice
     * of way in resting on the sources that can - see [Sources.sysfs].
     */
    override val publishesCapabilities = false

    /*
     * Either reader on its own is enough to say something. UCSI's supply is
     * the one with figures on it; the charger's class is what a board has
     * where the type-C side went a different way, and it still names the
     * adapter, which with the measurement below is an answer of sorts.
     */
    override fun present(sysfs: Sysfs) =
        UcsiSupply.name(sysfs) != null || QtiCharger.realType(sysfs) != null

    override fun read(sysfs: Sysfs): Snapshot? {
        val measured = ChargerSupply.measured(sysfs)

        val names = TypeCClass.ports(sysfs)
        val ports = if (names.isNotEmpty()) {
            /* Each port's own supply: the contract is per connector. */
            names.map { name ->
                val port = TypeCClass.port(sysfs, name)
                port.withContract(
                    UcsiSupply.contract(sysfs, port.connector).withCharger(sysfs, names.size)
                )
            }
        } else {
            val contract = UcsiSupply.contract(sysfs).withCharger(sysfs, ports = 1)
            /*
             * The type-C class is not always readable - on at least one Android
             * 16 build the shell is refused it - and what is left still says
             * whether something is attached: a supply that is online, or a
             * charger that is measuring something.
             */
            listOf(
                Port(
                    attached = contract.online || measured != null,
                    name = null,
                ).withContract(contract)
            )
        }

        return Snapshot(
            origin = Origin.UCSI_SUPPLY,
            ports = ports,
            measured = measured,
            emptyReason = when {
                ports.none { it.attached } -> EmptyReason.NOTHING_ATTACHED

                /*
                 * Only worth explaining where there was a list to miss. A
                 * charger that never spoke power delivery advertised nothing,
                 * so no interface could have published it.
                 */
                ports.any { it.inPowerDelivery() } ->
                    EmptyReason.NO_OBJECT_INTERFACE

                else -> null
            },
        )
    }

    private fun Port.withContract(contract: Contract): Port {
        val port = copy(protocol = contract.protocol, programmable = contract.programmable)
        if (!port.inPowerDelivery()) {
            return port
        }

        /*
         * Withheld rather than shown wrong: against a programmable supply
         * UCSI's two figures read the fixed-supply fields of an APDO and mean
         * nothing. The charger firmware is what makes that knowable here, and
         * where it says nothing they are shown with the caveat instead.
         */
        return port.copy(
            negotiatedMillivolts = contract.millivolts.takeUnless { contract.programmable == true },
            negotiatedMilliamps = contract.milliamps.takeUnless { contract.programmable == true },
        )
    }
}
