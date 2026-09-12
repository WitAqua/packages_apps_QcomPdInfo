/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import org.witaqua.qcom.pd_info.Renderer
import org.witaqua.qcom.pd_info.Section
import org.witaqua.qcom.pd_info.R
import org.witaqua.qcom.pd_info.io.Sysfs
import org.witaqua.qcom.pd_info.source.Sources
import java.util.concurrent.Executors

/*
 * How often the port is re-read while the screen is up. A negotiation settles
 * in tens of milliseconds, so this is not trying to catch one happening - it
 * is so that plugging a charger in while looking at the screen shows up
 * without leaving and coming back.
 */
private const val REFRESH_MS = 2000L

/**
 * The screen is built rather than inflated: how many ports a board has, and
 * how many supplies a charger advertises, are only known at the point of
 * reading them.
 */
class PdInfoFragment : PreferenceFragmentCompat() {
    private val handler = Handler(Looper.getMainLooper())

    /*
     * What the screen is currently made of - every heading and label, in
     * order. Values change on almost every read, and rebuilding the rows for
     * that makes the list blink; the structure only changes when a charger is
     * plugged or unplugged, which is the only time the rows have to be built
     * again.
     */
    private var shape: List<String>? = null

    /*
     * Reading may go through a root shell, which spawns a process, so it does
     * not belong on the main thread even though each read is small.
     */
    private val executor = Executors.newSingleThreadExecutor()

    private val refresher = object : Runnable {
        override fun run() {
            reload()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun reload() {
        val context = preferenceManager.context

        executor.execute {
            /*
             * Whichever way in works here. A platform-signed build on its own
             * ROM reads the files; anywhere else that is refused and a root
             * shell is the only way, so the choice follows what can be seen.
             */
            val sysfs: Sysfs = Sources.sysfs()
            val sections = Renderer(context).sections(Sources.read(sysfs))

            handler.post {
                if (isAdded) {
                    rebuild(sections)
                }
            }
        }
    }

    private fun rebuild(sections: List<Section>) {
        val newShape = sections.flatMap { section ->
            listOf(section.title) +
                section.rows.map { it.label } +
                listOfNotNull(section.note?.let { NOTE })
        }

        if (newShape == shape) {
            update(sections)
            return
        }

        shape = newShape
        build(sections)
    }

    /** Only the summaries, for when the screen is already the right shape. */
    private fun update(sections: List<Section>) {
        sections.forEachIndexed { index, section ->
            section.rows.forEachIndexed { row, (_, value) ->
                findPreference<Preference>("section$index.row$row")?.let {
                    /*
                     * Assigning the same text still notifies the adapter, and
                     * the adapter still rebinds, so compare first.
                     */
                    if (it.summary != value) {
                        it.summary = value
                    }
                }
            }

            section.note?.let { note ->
                findPreference<Preference>("section$index.$NOTE")?.let {
                    if (it.summary != note) {
                        it.summary = note
                    }
                }
            }
        }
    }

    private fun build(sections: List<Section>) {
        val context = preferenceManager.context
        val screen = preferenceScreen ?: return

        screen.removeAll()

        sections.forEachIndexed { index, section ->
            val category = PreferenceCategory(context).apply {
                key = "section$index"
                isIconSpaceReserved = false
                title = section.title
            }
            screen.addPreference(category)

            section.rows.forEachIndexed { row, (label, value) ->
                category.addPreference(
                    Preference(context).apply {
                        key = "section$index.row$row"
                        isSelectable = false
                        isIconSpaceReserved = false
                        title = label
                        summary = value
                    }
                )
            }

            /*
             * A note has no label of its own: it explains the rows above it,
             * and reads better as a paragraph than as a row with a blank key.
             */
            section.note?.let { note ->
                category.addPreference(
                    Preference(context).apply {
                        key = "section$index.$NOTE"
                        isSelectable = false
                        isIconSpaceReserved = false
                        summary = note
                        layoutResource = R.layout.pd_info_note
                    }
                )
            }
        }

        if (screen.preferenceCount == 0) {
            screen.addPreference(
                Preference(context).apply {
                    key = "empty"
                    isSelectable = false
                    isIconSpaceReserved = false
                    setTitle(R.string.section_unavailable)
                }
            )
        }
    }

    private companion object {
        const val NOTE = "note"
    }
}
