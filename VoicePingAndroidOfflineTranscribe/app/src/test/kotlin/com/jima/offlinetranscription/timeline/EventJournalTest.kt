package com.voiceping.offlinetranscription.timeline

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventJournalTest {
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("timeline-journal", ".ndjson")
        file.delete()
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun appendPersistsEscapedNdjsonRecordsInOrder() {
        val journal = EventJournal(file)
        journal.append(TimelineEvent("s1", 10L, "org.telegram.messenger", TimelineEventType.SCREEN_SNAPSHOT, "hello\n\"world\""))
        journal.append(TimelineEvent("s1", 20L, "org.telegram.messenger", TimelineEventType.ASR_FINAL, "final"))

        val records = journal.readRecords()
        assertEquals(2, records.size)
        assertTrue(records.first().contains("hello\\n\\\"world\\\""))
        assertTrue(records.last().contains("\"type\":\"ASR_FINAL\""))
    }
}
