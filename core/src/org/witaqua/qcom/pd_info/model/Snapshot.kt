/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.model

/**
 * Everything a source managed to find, in terms that do not say which kernel
 * interface it came out of. Each field is nullable because the two interfaces
 * carry different halves of this and neither carries all of it.
 */
data class Snapshot(
    /** Which reader produced this, for the screen to say so. */
    val origin: Origin,

    val ports: List<Port>,

    /** What the charger-facing power supply measures, where there is one. */
    val measured: Measured? = null,

    /**
     * Set when the interface exists but the kernel put nothing in it. Worth
     * separating from "no interface": the first is a platform choice that no
     * amount of privilege gets around, the second means look elsewhere.
     */
    val emptyReason: EmptyReason? = null,
) {
    val hasAnything: Boolean
        get() = ports.isNotEmpty() || measured != null
}

enum class Origin {
    /** Qualcomm's own driver: the 32-bit objects, including the request. */
    QUALCOMM,

    /** The upstream class: objects already in decimal, and no request. */
    UPSTREAM,

    /**
     * The type-C class and UCSI's power supply, which between them describe
     * the contract and carry no object list at all.
     */
    UCSI_SUPPLY,
}

enum class EmptyReason {
    /**
     * Nothing is plugged in, so there is nothing to have read. Kept apart from
     * the reason below because they look identical from the class - no
     * capabilities either way - and saying the wrong one is worse than saying
     * nothing: one is a cable, the other is a platform.
     */
    NOTHING_ATTACHED,

    /**
     * Something is attached and the upstream devices are registered, but they
     * hold no capabilities. With UCSI that is what happens when the firmware
     * does not report PDO details, or when the platform carries the quirk that
     * skips the partner's.
     */
    NO_CAPABILITIES_REGISTERED,

    /**
     * Nothing on this kernel publishes an object list: no qualcomm driver, no
     * upstream class, no UCSI debugfs. Unlike the reason above the driver did
     * read the objects - there is only nowhere to read them back out of, so
     * what UCSI derived from them is all there is. See
     * docs/5.10_xiaomi-sm8450.md for the kernel this was written for.
     */
    NO_OBJECT_INTERFACE,
}

data class Port(
    /**
     * Whether anything is on the other end. The type-C class drops the partner
     * device when the cable comes out, which is the plainest signal there is.
     */
    val attached: Boolean = true,

    /**
     * The type-C port this belongs to, or null when the class that names it
     * could not be read - which happens, and is no reason to drop the rest.
     */
    val name: String?,

    /** "sink", "source", "none" - already reduced from any bracketed list. */
    val powerRole: String? = null,
    val dataRole: String? = null,

    /** "explicit"/"implicit" on qualcomm, "usb_power_delivery" upstream. */
    val contract: String? = null,

    val pdRevision: String? = null,
    val partnerSupportsPd: Boolean? = null,

    /** What the other end advertised. Empty when it was never read. */
    val capabilities: List<SourceCapability> = emptyList(),

    /** What this end asked for, where the interface exposes it. */
    val request: Request? = null,

    /**
     * The negotiated current when the request object itself is out of reach.
     * UCSI's power supply derives this from the request, so it is an agreed
     * figure rather than a measurement.
     */
    val negotiatedMilliamps: Int? = null,

    /**
     * The negotiated voltage, likewise - UCSI takes it from the source object
     * the request names, which it can only do where the objects were read. It
     * reads the fixed-supply field of that object whatever type it is, so it
     * comes with the same caveat as the current beside it and is shown with
     * one.
     */
    val negotiatedMillivolts: Int? = null,

    /** "PD", "PD_PPS", "C", "BC1.2" - the protocol actually in force. */
    val protocol: String? = null,

    /**
     * Whether the contract is against a programmable supply, on a source that
     * has no object list to say so itself. Null where nothing on the board
     * publishes it, which is why this is not a plain boolean: it separates "a
     * fixed contract, so the figures above hold" from "nobody said".
     */
    val programmable: Boolean? = null,
)

data class Measured(
    val supply: String,
    val type: String? = null,
    val millivolts: Int? = null,
    val milliamps: Int? = null,
)
