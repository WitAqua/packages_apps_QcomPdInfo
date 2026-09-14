/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info

import android.content.Context
import org.witaqua.qcom.pd_info.model.EmptyReason
import org.witaqua.qcom.pd_info.model.Measured
import org.witaqua.qcom.pd_info.model.Origin
import org.witaqua.qcom.pd_info.model.Port
import org.witaqua.qcom.pd_info.model.Request
import org.witaqua.qcom.pd_info.model.Snapshot
import org.witaqua.qcom.pd_info.model.SourceCapability
import org.witaqua.qcom.pd_info.model.SourceFlags
import java.text.NumberFormat

/** A heading and the lines under it, which is all either screen needs. */
data class Section(val title: String, val rows: List<Row>, val note: String? = null)

data class Row(val label: String, val value: String)

/**
 * Turning a snapshot into the lines somebody reads. Kept away from both the
 * decoding and the two user interfaces: what the specification says, how it is
 * worded, and where it is drawn are three separate arguments.
 */
class Renderer(private val context: Context) {
    fun sections(snapshot: Snapshot?): List<Section> {
        if (snapshot == null || !snapshot.hasAnything) {
            return listOf(unavailable())
        }

        /*
         * With nothing plugged in there is one fact worth showing and a page
         * of empty rows not worth showing. The roles a port would take, the
         * revision it would speak and the protocol it is not using say
         * nothing, and putting the object list's absence down to the platform
         * would be wrong - the cable is out.
         */
        if (snapshot.emptyReason == EmptyReason.NOTHING_ATTACHED) {
            return listOf(detached(snapshot))
        }

        return buildList {
            snapshot.ports.forEach { port ->
                add(portSection(port))
                if (port.capabilities.isNotEmpty()) {
                    add(capabilitySection(port))
                }
                contractSection(port, snapshot.origin)?.let { add(it) }
            }

            snapshot.measured?.let { add(measuredSection(it, snapshot.origin)) }
            emptyNote(snapshot)?.let { add(it) }
        }
    }

    /**
     * One line for the band at the top: what is on the other end of the
     * cable, and at what. It is the answer somebody opened this for, and the
     * table below is the working.
     */
    fun headline(snapshot: Snapshot?): String {
        val port = snapshot?.ports?.firstOrNull()
            ?: return context.getString(R.string.headline_nothing)

        if (snapshot.emptyReason == EmptyReason.NOTHING_ATTACHED) {
            return context.getString(R.string.headline_nothing)
        }

        /*
         * The request says what was taken; where it is out of reach the
         * measurement is the nearest thing to it.
         */
        val taken = port.request?.let { request ->
            when (request) {
                is Request.Programmable -> context.getString(
                    R.string.headline_at,
                    volts(request.millivolts),
                    amps(request.operatingMilliamps),
                )

                is Request.Current -> port.capabilities
                    .filterIsInstance<SourceCapability.Fixed>()
                    .firstOrNull { it.position == request.objectPosition }
                    ?.let {
                        context.getString(
                            R.string.headline_at,
                            volts(it.millivolts),
                            amps(request.operatingMilliamps),
                        )
                    }

                else -> null
            }
        } ?: snapshot.measured?.let { measured ->
            val millivolts = measured.millivolts ?: return@let null
            context.getString(
                R.string.headline_at,
                volts(millivolts),
                amps(measured.milliamps ?: 0),
            )
        }

        val protocol = port.protocol ?: port.contract?.let { word(it) }

        return listOfNotNull(protocol, taken)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(context.getString(R.string.list_separator))
            ?: context.getString(R.string.headline_nothing)
    }

    private fun portSection(port: Port) = Section(
        title = port.name
            ?.let { context.getString(R.string.section_port, it) }
            ?: context.getString(R.string.section_port_unnamed),
        rows = buildList {
            port.contract?.let { add(Row(context.getString(R.string.label_contract), word(it))) }
            port.powerRole?.let { add(Row(context.getString(R.string.label_power_role), word(it))) }
            port.dataRole?.let { add(Row(context.getString(R.string.label_data_role), word(it))) }
            port.protocol?.let { add(Row(context.getString(R.string.label_protocol), it)) }
            port.pdRevision?.let { add(Row(context.getString(R.string.label_pd_revision), it)) }
            port.partnerSupportsPd?.let {
                add(
                    Row(
                        context.getString(R.string.label_partner),
                        context.getString(
                            if (it) R.string.partner_supports_pd else R.string.partner_no_pd
                        ),
                    )
                )
            }
        },
    )

    private fun capabilitySection(port: Port) = Section(
        title = context.getString(R.string.section_advertised),
        rows = port.capabilities.map { capability ->
            Row(
                context.getString(R.string.label_object, capability.position),
                capability(capability),
            )
        },
        note = port.capabilities
            .filterIsInstance<SourceCapability.Fixed>()
            .firstNotNullOfOrNull { it.flags }
            ?.let { flags(it) },
    )

    /*
     * What was actually taken. The interfaces answer this differently - one has
     * the request object itself, the others only what UCSI derived from it - so
     * the rows say which it is rather than pretending they are the same thing.
     */
    private fun contractSection(port: Port, origin: Origin): Section? {
        /*
         * Only where the request object itself is out of reach. UCSI's power
         * supply works both of these out with rdo_op_current() and
         * pdo_fixed_voltage(), which read the fixed-supply fields whatever the
         * request actually is - against a programmable supply that lands on the
         * wrong bits and gives figures that mean nothing. Where the request
         * could be read, it has already been read properly.
         */
        val derived = port.request == null

        val rows = buildList {
            port.request?.let { add(Row(context.getString(R.string.label_request), request(it))) }

            if (derived) {
                port.negotiatedMillivolts?.let {
                    add(
                        Row(
                            context.getString(R.string.label_negotiated_voltage),
                            context.getString(R.string.value_volts, volts(it)),
                        )
                    )
                }
                port.negotiatedMilliamps?.let {
                    add(
                        Row(
                            context.getString(R.string.label_negotiated_current),
                            context.getString(R.string.value_amps, amps(it)),
                        )
                    )
                }
            }
        }
        val note = when {
            !derived -> null

            /*
             * The one case worth a heading with nothing under it: the figures
             * were withheld on purpose, and saying why is the whole content.
             */
            port.programmable == true ->
                context.getString(R.string.note_contract_programmable)

            rows.isEmpty() -> null

            /*
             * The upstream class arrives with the objects but without a
             * voltage, so it gets the narrower line. Where there is no object
             * list the reader has nothing to weigh either figure against, and
             * what can be said depends on whether the charger firmware named
             * the kind of contract.
             */
            origin != Origin.UCSI_SUPPLY ->
                context.getString(R.string.note_current_from_request)

            port.programmable == false ->
                context.getString(R.string.note_contract_fixed_supply)

            else -> context.getString(R.string.note_contract_from_supply)
        }

        if (rows.isEmpty() && note == null) {
            return null
        }

        return Section(
            title = context.getString(R.string.section_in_use),
            rows = rows,
            note = note,
        )
    }

    private fun measuredSection(measured: Measured, origin: Origin) = Section(
        title = context.getString(R.string.section_measured, measured.supply),
        rows = buildList {
            measured.type?.let { add(Row(context.getString(R.string.label_reported_as), it)) }
            measured.millivolts?.let {
                add(
                    Row(
                        context.getString(R.string.label_voltage),
                        context.getString(R.string.value_volts, volts(it)),
                    )
                )
            }
            measured.milliamps?.let {
                add(
                    Row(
                        context.getString(R.string.label_current),
                        context.getString(R.string.value_amps, amps(it)),
                    )
                )
            }
        },
        note = if (origin == Origin.UPSTREAM) {
            context.getString(R.string.note_measured_not_agreed)
        } else {
            null
        },
    )

    /**
     * Nothing on the other end. One row saying which port, one saying what it
     * would do when something arrives, and a line to explain the emptiness so
     * that it does not read as a failure to find anything.
     */
    private fun detached(snapshot: Snapshot) = Section(
        title = context.getString(R.string.section_state),
        rows = buildList {
            snapshot.ports.firstOrNull()?.name?.let {
                add(Row(context.getString(R.string.label_port), it))
            }
            add(
                Row(
                    context.getString(R.string.label_state),
                    context.getString(R.string.state_detached),
                )
            )
            snapshot.ports.firstOrNull()?.pdRevision?.let {
                add(Row(context.getString(R.string.label_pd_revision), it))
            }
        },
        note = context.getString(R.string.note_detached),
    )

    private fun emptyNote(snapshot: Snapshot): Section? =
        when (snapshot.emptyReason) {
            EmptyReason.NO_CAPABILITIES_REGISTERED -> Section(
                title = context.getString(R.string.section_why_empty),
                rows = emptyList(),
                note = context.getString(R.string.note_no_capabilities),
            )

            EmptyReason.NO_OBJECT_INTERFACE -> Section(
                title = context.getString(R.string.section_why_empty),
                rows = emptyList(),
                note = context.getString(R.string.note_no_object_interface),
            )

            /* Handled before any of this, by returning a page of its own. */
            EmptyReason.NOTHING_ATTACHED, null -> null
        }

    private fun unavailable() = Section(
        title = context.getString(R.string.section_unavailable),
        rows = listOf(
            Row(context.getString(R.string.label_platform), Platform.platform.ifEmpty { "?" }),
            Row(context.getString(R.string.label_kernel), Platform.kernelRelease),
        ),
        note = context.getString(R.string.note_unavailable),
    )

    fun capability(capability: SourceCapability): String =
        when (capability) {
            is SourceCapability.Fixed -> context.getString(
                R.string.capability_fixed,
                volts(capability.millivolts),
                amps(capability.maxMilliamps),
            )

            is SourceCapability.Battery -> context.getString(
                R.string.capability_battery,
                volts(capability.minMillivolts),
                volts(capability.maxMillivolts),
                watts(capability.maxMilliwatts),
            )

            is SourceCapability.Variable -> context.getString(
                R.string.capability_variable,
                volts(capability.minMillivolts),
                volts(capability.maxMillivolts),
                amps(capability.maxMilliamps),
            )

            is SourceCapability.Programmable -> context.getString(
                R.string.capability_programmable,
                volts(capability.minMillivolts),
                volts(capability.maxMillivolts),
                amps(capability.maxMilliamps),
            )

            is SourceCapability.Adjustable -> context.getString(
                R.string.capability_adjustable,
                amps(capability.milliampsAt9To15V),
                amps(capability.milliampsAt15To20V),
            )

            /*
             * A supply type added after this was written. The word it came as
             * is more use than nothing at all.
             */
            is SourceCapability.Unknown -> context.getString(
                R.string.capability_unknown,
                "0x%08x".format(capability.word),
            )
        }

    private fun request(request: Request): String =
        when (request) {
            is Request.Current -> context.getString(
                R.string.request_current,
                request.objectPosition,
                amps(request.operatingMilliamps),
            )

            is Request.Power -> context.getString(
                R.string.request_power,
                request.objectPosition,
                watts(request.operatingMilliwatts),
            )

            is Request.Programmable -> context.getString(
                R.string.request_programmable,
                request.objectPosition,
                volts(request.millivolts),
                amps(request.operatingMilliamps),
            )

            is Request.Unknown -> context.getString(
                R.string.capability_unknown,
                "0x%08x".format(request.word),
            )
        }

    /*
     * The source-wide bits, as the few of them worth a line. Unconstrained
     * power is the one that says whether what is offered is backed by the wall
     * or by a battery the charger is also living off.
     */
    private fun flags(flags: SourceFlags): String? =
        buildList {
            if (flags.unconstrainedPower) add(context.getString(R.string.flag_unconstrained))
            if (flags.dualRolePower) add(context.getString(R.string.flag_dual_role_power))
            if (flags.dualRoleData) add(context.getString(R.string.flag_dual_role_data))
            if (flags.usbCommunicationsCapable) add(context.getString(R.string.flag_usb_comms))
            if (flags.suspendSupported) add(context.getString(R.string.flag_suspend))
        }.takeIf { it.isNotEmpty() }
            ?.joinToString(context.getString(R.string.list_separator))
            ?.let { context.getString(R.string.note_source_flags, it) }

    /*
     * The driver's own words, expanded. They are exact and say little to
     * anybody who has not read the specification; anything unrecognised is
     * passed through rather than dropped.
     */
    private fun word(raw: String): String =
        when (raw) {
            "source" -> context.getString(R.string.role_source)
            "sink" -> context.getString(R.string.role_sink)
            "dfp", "host" -> context.getString(R.string.role_host)
            "ufp", "device" -> context.getString(R.string.role_device)
            "none" -> context.getString(R.string.role_none)
            "explicit" -> context.getString(R.string.contract_explicit)
            "implicit" -> context.getString(R.string.contract_implicit)
            "usb_power_delivery" -> context.getString(R.string.contract_explicit)
            else -> raw
        }

    /*
     * Volts lose a trailing ".0" - 9V, not 9.0V - while amps keep a digit,
     * because a supply rated at exactly three amperes is still quoted as 3.0A.
     * Both stop at two, which is finer than any of the encodings go.
     */
    private fun volts(millivolts: Int) = scaled(millivolts, 0)

    private fun amps(milliamps: Int) = scaled(milliamps, 1)

    private fun watts(milliwatts: Int) = scaled(milliwatts, 0)

    private fun scaled(milli: Int, minimumDigits: Int): String =
        NumberFormat.getInstance().apply {
            minimumFractionDigits = minimumDigits
            maximumFractionDigits = 2
        }.format(milli / 1000.0)
}
