package com.ospchat.shared.data.attachments

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileAttachmentStoreTest {
    private lateinit var tmpDir: File
    private lateinit var store: FileAttachmentStore

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("attachment-store-test").toFile()
        store = FileAttachmentStore(parentDir = tmpDir)
    }

    @AfterTest
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun roundTrip() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val path = store.writeBytes("msg-1", bytes)
        assertTrue(store.exists("msg-1"))
        assertEquals(path, store.pathFor("msg-1"))
        assertContentEquals(bytes, File(path).readBytes())
    }

    @Test
    fun missingMessage() {
        assertFalse(store.exists("nope"))
    }
}
