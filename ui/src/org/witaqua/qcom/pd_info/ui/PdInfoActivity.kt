/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.witaqua.qcom.pd_info.R

/*
 * One screen, and the same one in both builds. What differs between them is
 * how a file in /sys may be opened, and that is decided by reading rather
 * than by which apk this is - see source/Sources.kt.
 */
class PdInfoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pd_info)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.content, PdInfoFragment())
                .commit()
        }
    }
}
