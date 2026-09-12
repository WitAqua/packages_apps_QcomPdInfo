/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.model

/*
 * The wire format of a USB Power Delivery data object, as the specification
 * lays it out. Nothing here is device specific: a source capability is 32 bits
 * with the same meaning on every port that speaks power delivery, and the
 * driver hands it over untouched.
 *
 * Qualcomm's policy engine publishes them as eight bare hex words:
 *
 *   /sys/class/usbpd/usbpd0/pdo1 .. pdo7    what the other end advertised
 *   /sys/class/usbpd/usbpd0/rdo              what was asked for
 *
 * There is a pdo_h alongside them that the kernel has already put into
 * sentences, but it is several lines per object and the wording differs
 * between kernel versions, so the word itself is the steadier thing to read.
 */

/** One entry of a source's advertised capabilities. */
sealed interface SourceCapability {
    /** Where in the advertisement this one sat. Requests index by it. */
    val position: Int

    /** A single voltage, and the most that may be drawn at it. */
    data class Fixed(
        override val position: Int,
        val millivolts: Int,
        val maxMilliamps: Int,
        val flags: SourceFlags?,
    ) : SourceCapability

    /** A power budget across a voltage range, for a battery source. */
    data class Battery(
        override val position: Int,
        val minMillivolts: Int,
        val maxMillivolts: Int,
        val maxMilliwatts: Int,
    ) : SourceCapability

    /** A current limit across a voltage range the source picks. */
    data class Variable(
        override val position: Int,
        val minMillivolts: Int,
        val maxMillivolts: Int,
        val maxMilliamps: Int,
    ) : SourceCapability

    /** Programmable power supply: the sink chooses the voltage, in steps. */
    data class Programmable(
        override val position: Int,
        val minMillivolts: Int,
        val maxMillivolts: Int,
        val maxMilliamps: Int,
        val powerLimited: Boolean,
    ) : SourceCapability

    /** A supply type this build does not know how to read. */
    data class Unknown(override val position: Int, val word: Long) : SourceCapability

    /**
     * An adjustable voltage supply, which quotes a current per voltage band
     * rather than one figure. Added in power delivery 3.2; the upstream class
     * publishes it, the 32-bit reader here does not decode it yet.
     */
    data class Adjustable(
        override val position: Int,
        val milliampsAt9To15V: Int,
        val milliampsAt15To20V: Int,
    ) : SourceCapability
}

/*
 * The bits a source uses to describe itself rather than one of its supplies.
 * They are only meaningful in the first fixed capability - the specification
 * reserves them everywhere else - so they are read from that one alone.
 */
data class SourceFlags(
    val dualRolePower: Boolean,
    val suspendSupported: Boolean,
    val unconstrainedPower: Boolean,
    val usbCommunicationsCapable: Boolean,
    val dualRoleData: Boolean,
)

/** What the sink asked the source for, and out of which capability. */
sealed interface Request {
    val objectPosition: Int

    /** Against a fixed or variable supply, which are requested by current. */
    data class Current(
        override val objectPosition: Int,
        val operatingMilliamps: Int,
        val limitMilliamps: Int,
    ) : Request

    /** Against a battery supply, which is requested by power. */
    data class Power(
        override val objectPosition: Int,
        val operatingMilliwatts: Int,
        val limitMilliwatts: Int,
    ) : Request

    /** Against a programmable supply, which carries the chosen voltage. */
    data class Programmable(
        override val objectPosition: Int,
        val millivolts: Int,
        val operatingMilliamps: Int,
    ) : Request

    data class Unknown(override val objectPosition: Int, val word: Long) : Request
}

object PowerDeliveryObject {
    private const val TYPE_FIXED = 0L
    private const val TYPE_BATTERY = 1L
    private const val TYPE_VARIABLE = 2L
    private const val TYPE_AUGMENTED = 3L

    /* Only one augmented type is defined so far, and it is the programmable one. */
    private const val AUGMENTED_PROGRAMMABLE = 0L

    /**
     * Reads one source capability out of its 32-bit word. [position] is where
     * it sat in the advertisement, counting from one, and [first] selects
     * whether the source-wide flags are taken from it - which the specification
     * only allows for the first fixed supply.
     */
    fun capability(word: Long, position: Int, first: Boolean): SourceCapability =
        when (word ushr 30 and 0x3) {
            TYPE_FIXED -> SourceCapability.Fixed(
                position = position,
                /* 50 mV and 10 mA steps. */
                millivolts = (word ushr 10 and 0x3FF).toInt() * 50,
                maxMilliamps = (word and 0x3FF).toInt() * 10,
                flags = if (first) flags(word) else null,
            )

            TYPE_BATTERY -> SourceCapability.Battery(
                position = position,
                minMillivolts = (word ushr 10 and 0x3FF).toInt() * 50,
                maxMillivolts = (word ushr 20 and 0x3FF).toInt() * 50,
                /* Power is in 250 mW steps here, not current. */
                maxMilliwatts = (word and 0x3FF).toInt() * 250,
            )

            TYPE_VARIABLE -> SourceCapability.Variable(
                position = position,
                minMillivolts = (word ushr 10 and 0x3FF).toInt() * 50,
                maxMillivolts = (word ushr 20 and 0x3FF).toInt() * 50,
                maxMilliamps = (word and 0x3FF).toInt() * 10,
            )

            TYPE_AUGMENTED -> if (word ushr 28 and 0x3 == AUGMENTED_PROGRAMMABLE) {
                SourceCapability.Programmable(
                    position = position,
                    /* Coarser fields than the rest: 100 mV and 50 mA steps. */
                    minMillivolts = (word ushr 8 and 0xFF).toInt() * 100,
                    maxMillivolts = (word ushr 17 and 0xFF).toInt() * 100,
                    maxMilliamps = (word and 0x7F).toInt() * 50,
                    powerLimited = word ushr 27 and 0x1 == 1L,
                )
            } else {
                SourceCapability.Unknown(position, word)
            }

            else -> SourceCapability.Unknown(position, word)
        }

    /**
     * Reads the request. Which fields it carries follows the capability it was
     * made against, so [against] is the one at its object position - the same
     * lookup the kernel does to render this.
     */
    fun request(word: Long, against: SourceCapability?): Request {
        /*
         * Object positions count from one, and zero means no contract: a
         * request read while nothing is negotiated has nothing behind it.
         */
        val position = (word ushr 28 and 0x7).toInt()

        return when (against) {
            is SourceCapability.Fixed, is SourceCapability.Variable ->
                Request.Current(
                    objectPosition = position,
                    operatingMilliamps = (word ushr 10 and 0x3FF).toInt() * 10,
                    limitMilliamps = (word and 0x3FF).toInt() * 10,
                )

            is SourceCapability.Battery ->
                Request.Power(
                    objectPosition = position,
                    operatingMilliwatts = (word ushr 10 and 0x3FF).toInt() * 250,
                    limitMilliwatts = (word and 0x3FF).toInt() * 250,
                )

            is SourceCapability.Programmable ->
                Request.Programmable(
                    objectPosition = position,
                    /* 20 mV steps, which is the resolution a sink may ask in. */
                    millivolts = (word ushr 9 and 0x7FF).toInt() * 20,
                    operatingMilliamps = (word and 0x7F).toInt() * 50,
                )

            else -> Request.Unknown(position, word)
        }
    }

    /** The object position a request names, without reading the rest of it. */
    fun requestPosition(word: Long) = (word ushr 28 and 0x7).toInt()

    /**
     * Whether this looks like something a source actually advertised.
     *
     * Needed because one way of reading the objects - putting GET_PDOS to the
     * policy manager by hand - gets a fixed-size response with no count in it,
     * so whatever the manager had in its buffer past the real objects comes
     * back as well. Observed twice on one handset, differently each time: one
     * charger left the previous reply's objects there, another left 0x00000002.
     * Neither is zero and neither repeats, so only asking whether the thing
     * decodes to a supply that could exist will do.
     *
     * Every supply type has to name a voltage it can deliver and something it
     * can deliver at it; a range has to run the right way round.
     */
    fun isPlausible(capability: SourceCapability): Boolean =
        when (capability) {
            is SourceCapability.Fixed ->
                capability.millivolts > 0 && capability.maxMilliamps > 0

            is SourceCapability.Battery ->
                capability.minMillivolts > 0 &&
                    capability.maxMillivolts >= capability.minMillivolts &&
                    capability.maxMilliwatts > 0

            is SourceCapability.Variable ->
                capability.minMillivolts > 0 &&
                    capability.maxMillivolts >= capability.minMillivolts &&
                    capability.maxMilliamps > 0

            is SourceCapability.Programmable ->
                capability.minMillivolts > 0 &&
                    capability.maxMillivolts >= capability.minMillivolts &&
                    capability.maxMilliamps > 0

            is SourceCapability.Adjustable ->
                capability.milliampsAt9To15V > 0

            /* A supply type added after this was written is worth showing. */
            is SourceCapability.Unknown -> capability.word != 0L
        }

    private fun flags(word: Long) = SourceFlags(
        dualRolePower = word ushr 29 and 0x1 == 1L,
        suspendSupported = word ushr 28 and 0x1 == 1L,
        unconstrainedPower = word ushr 27 and 0x1 == 1L,
        usbCommunicationsCapable = word ushr 26 and 0x1 == 1L,
        dualRoleData = word ushr 25 and 0x1 == 1L,
    )
}
