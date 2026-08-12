package com.voiceping.offlinetranscription.timeline

import java.io.File
import java.io.FileOutputStream

/**
 * Append-only, locally stored write-ahead journal. Call [append] before writing
 * screenshots, OCR results, or reports so a restarted process can recover context.
 */
class EventJournal(private val file: File) {
    @Synchronized
    fun append(event: TimelineEvent) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { output ->
            output.write((event.toNdjson() + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    fun readRecords(): List<String> = if (file.exists()) file.readLines(Charsets.UTF_8) else emptyList()

    private fun TimelineEvent.toNdjson(): String = buildString {
        append('{')
        appendJson("sessionId", sessionId); append(',')
        appendJson("timestampMillis", timestampMillis.toString(), quoted = false); append(',')
        appendJson("packageName", packageName); append(',')
        appendJson("type", type.name); append(',')
        appendJson("summary", summary); append(',')
        appendJson("recovered", recovered.toString(), quoted = false)
        append('}')
    }

    private fun StringBuilder.appendJson(name: String, value: String, quoted: Boolean = true) {
        append('"').append(name).append("\":")
        if (quoted) append('"').append(value.jsonEscape()).append('"') else append(value)
    }

    private fun String.jsonEscape(): String = buildString {
        this@jsonEscape.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}
