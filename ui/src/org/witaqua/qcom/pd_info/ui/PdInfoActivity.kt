/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.witaqua.qcom.pd_info.R
import org.witaqua.qcom.pd_info.Renderer
import org.witaqua.qcom.pd_info.Section
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
 * A table of readings, in one screen, built rather than inflated: how many
 * ports a board has and how many supplies a charger advertises are only known
 * at the point of reading them.
 *
 * Laid out as a table rather than as a list of cards because the point of it
 * is to take the whole in at once - a row of padding per reading would put
 * half of it off the bottom.
 */
class PdInfoActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())

    /*
     * Reading may go through a root shell, which spawns a process, so it does
     * not belong on the main thread even though each read is small.
     */
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var table: TableLayout
    private lateinit var title: TextView
    private lateinit var summary: TextView

    /*
     * What the table is currently made of - every heading and label, in order.
     * Values change on almost every read, and rebuilding the rows for that
     * makes the screen blink; the structure only changes when a charger is
     * plugged or unplugged.
     */
    private var shape: List<String>? = null
    private val values = mutableMapOf<String, TextView>()

    private val refresher = object : Runnable {
        override fun run() {
            reload()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pd_info)

        table = findViewById(R.id.table)
        title = findViewById(R.id.header_title)
        summary = findViewById(R.id.header_summary)
        title.text = getString(R.string.app_name)
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
        executor.execute {
            /*
             * Whichever way in works here. A platform-signed build on its own
             * ROM reads the files; anywhere else that is refused and a root
             * shell is the only way, so the choice follows what can be seen.
             */
            val sysfs: Sysfs = Sources.sysfs()
            val snapshot = Sources.read(sysfs)
            val sections = Renderer(this).sections(snapshot)
            val headline = Renderer(this).headline(snapshot)

            handler.post {
                if (!isFinishing) {
                    summary.text = headline
                    apply(sections)
                }
            }
        }
    }

    private fun apply(sections: List<Section>) {
        val newShape = sections.flatMap { section ->
            listOf(section.title) +
                section.rows.map { it.label } +
                listOfNotNull(section.note?.let { NOTE })
        }

        if (newShape == shape) {
            sections.forEachIndexed { index, section ->
                section.rows.forEachIndexed { row, (_, value) ->
                    values["$index.$row"]?.let { if (it.text != value) it.text = value }
                }
                section.note?.let { values["$index.$NOTE"]?.text = it }
            }
            return
        }

        shape = newShape
        build(sections)
    }

    private fun build(sections: List<Section>) {
        val inflater = LayoutInflater.from(this)
        table.removeAllViews()
        values.clear()

        /*
         * The shading runs across the whole table rather than restarting per
         * section: it is there to separate one row from the next, and a
         * heading already separates one section from the next.
         */
        var striped = 0

        sections.forEachIndexed { index, section ->
            table.addView(
                (inflater.inflate(R.layout.pd_info_section, table, false) as TextView)
                    .apply { text = section.title }
            )

            section.rows.forEachIndexed { row, (label, value) ->
                val view = inflater.inflate(R.layout.pd_info_row, table, false) as TableRow
                view.setBackgroundResource(
                    if (striped++ % 2 == 0) R.color.row_background_odd
                    else R.color.row_background_even
                )
                view.findViewById<TextView>(R.id.label).text = label
                val cell = view.findViewById<TextView>(R.id.value)
                cell.text = value
                values["$index.$row"] = cell
                table.addView(view)
            }

            section.note?.let { note ->
                val view = inflater.inflate(R.layout.pd_info_note, table, false) as TextView
                view.text = note
                values["$index.$NOTE"] = view
                table.addView(view)
            }
        }
    }

    private companion object {
        const val NOTE = "note"
    }
}
