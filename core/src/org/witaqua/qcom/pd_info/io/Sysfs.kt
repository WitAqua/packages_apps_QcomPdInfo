/*
 * Copyright (C) 2026 The WitAqua Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.witaqua.qcom.pd_info.io

import android.util.Log
import java.io.File
import java.io.IOException

private const val TAG = "PdInfo"

/*
 * Where the numbers come from. The same screen is wanted in two places that
 * differ only in how a file in /sys may be opened:
 *
 *   - built into the ROM, where the device tree labels the nodes and the app
 *     reads them itself;
 *   - installed on somebody else's ROM, where it may not, and the only way in
 *     is to ask a root shell.
 *
 * Reads are asked for in batches because the second case pays a process per
 * call, and a screenful is a few dozen files.
 */
interface Sysfs {
    /** Names within a directory, empty when it cannot be listed. */
    fun list(directory: String): List<String>

    /** The readable subset of [paths], trimmed. Unreadable ones are absent. */
    fun read(paths: Collection<String>): Map<String, String>

    fun read(path: String): String? = read(listOf(path))[path]

    /**
     * Writes one value. Only the UCSI debugfs interface needs this, and only
     * to ask a question - see [org.witaqua.qcom.pd_info.source.UcsiDebugfs].
     * Defaults to refusing, so a reader is read-only unless it says otherwise.
     */
    fun write(path: String, value: String): Boolean = false

    /** Whether this way in works at all, so the caller can say why it does not. */
    fun available(): Boolean
}

/** Reading the files directly, which is what a system app on its own ROM does. */
object DirectSysfs : Sysfs {
    override fun list(directory: String): List<String> =
        File(directory).list()?.sorted() ?: emptyList()

    override fun read(paths: Collection<String>): Map<String, String> =
        paths.mapNotNull { path ->
            try {
                File(path).readText().trim().ifEmpty { null }?.let { path to it }
            } catch (e: IOException) {
                /* Absent, or not labelled for this app. Neither is worth a throw. */
                null
            }
        }.toMap()

    override fun write(path: String, value: String): Boolean =
        try {
            File(path).writeText(value)
            true
        } catch (e: IOException) {
            Log.d(TAG, "could not write $path", e)
            false
        }

    override fun available() = File("/sys/class").isDirectory
}

/**
 * Reading through a root shell. One shell per batch: spawning `su` is the
 * expensive part, and some superuser implementations prompt per invocation.
 */
object RootSysfs : Sysfs {
    override fun list(directory: String): List<String> =
        shell("ls -1 '${directory.shellSafe()}' 2>/dev/null")
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.sorted()
            ?: emptyList()

    override fun read(paths: Collection<String>): Map<String, String> {
        if (paths.isEmpty()) {
            return emptyMap()
        }

        /*
         * Tab separated, one record per file, skipping what is not there: none
         * of these values contains a tab, and the ones that contain spaces -
         * "source [sink]", "C [PD] PD_PPS" - survive it intact.
         */
        val script = paths.joinToString("\n") { path ->
            val quoted = "'${path.shellSafe()}'"
            "[ -r $quoted ] && printf '%s\\t%s\\n' $quoted \"\$(cat $quoted 2>/dev/null | tr -d '\\r\\n')\""
        } +
            /*
             * The status of the last line is the status of its own test, and a
             * file that is not there is ordinary rather than a failure - a
             * charger being unplugged takes the partner's attributes with it.
             * Without this the whole batch would be thrown away over the last
             * path, so say plainly that running the script is the success.
             */
            "\nexit 0"

        return shell(script)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1).trim()
            }
            ?.filter { it.second.isNotEmpty() }
            ?.toMap()
            ?: emptyMap()
    }

    override fun write(path: String, value: String): Boolean =
        /*
         * The shell's own status is the answer here: a refused write is the
         * question not being asked rather than a missing file.
         */
        shell("printf '%s' '${value.shellSafe()}' > '${path.shellSafe()}'") != null

    override fun available() = shell("id -u")?.trim() == "0"

    private fun shell(script: String): String? =
        try {
            val process = ProcessBuilder("su", "-c", "sh").redirectErrorStream(false).start()
            process.outputStream.bufferedWriter().use { it.write(script) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            /*
             * Non-zero here means the script did not run at all - no su on the
             * device, or the prompt was refused. Whether any particular file
             * was readable is the caller's business, and is answered by what is
             * on stdout.
             */
            if (process.waitFor() == 0) output else null
        } catch (e: IOException) {
            Log.d(TAG, "no root shell", e)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

    /* These paths are all built from kernel-supplied names, but none of them
     * has any business carrying a quote. */
    private fun String.shellSafe() = replace("'", "")
}
