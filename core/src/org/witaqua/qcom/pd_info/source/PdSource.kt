/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.source

import org.witaqua.qcom.pd_info.io.Sysfs
import org.witaqua.qcom.pd_info.model.Snapshot

/** One kernel interface that power delivery state can be read out of. */
interface PdSource {
    /** Cheap enough to call before deciding: a directory test, no reads. */
    fun present(sysfs: Sysfs): Boolean

    fun read(sysfs: Sysfs): Snapshot?

    /**
     * Whether this one can produce the charger's object list. Only a source
     * that can is allowed to settle the way in: one that reports the contract
     * and nothing else being readable without help is no reason to stay out of
     * a root shell that would have got the list. See [Sources.sysfs].
     */
    val publishesCapabilities: Boolean
        get() = true
}

/*
 * Several type-C attributes list every value the port supports and bracket the
 * one in force - "source [sink]", "C [PD] PD_PPS". Anything unbracketed is a
 * single value already.
 */
internal fun String.activeValue(): String {
    val open = indexOf('[')
    val close = indexOf(']', open + 1)
    return if (open >= 0 && close > open) substring(open + 1, close) else trim()
}
