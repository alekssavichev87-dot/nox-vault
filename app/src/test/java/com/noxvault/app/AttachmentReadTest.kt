package com.noxvault.app

import com.noxvault.app.viewmodel.readBytesLimited
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Регрессия к падению v1.0/v1.1.0 при прикреплении файла:
 * чтение вложения обязано быть порционным и ограниченным лимитом,
 * а не «весь файл в память» (OOM на больших файлах с неизвестным размером).
 */
class AttachmentReadTest {

    @Test
    fun underLimit_returnsAllBytes() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        val result = readBytesLimited(ByteArrayInputStream(data), maxBytes = 2048)
        assertArrayEquals(data, result)
    }

    @Test
    fun exactlyAtLimit_isAccepted() {
        val data = ByteArray(2048) { 7 }
        val result = readBytesLimited(ByteArrayInputStream(data), maxBytes = 2048)
        assertEquals(2048, result?.size)
    }

    @Test
    fun overLimit_returnsNullInsteadOfLoadingEverything() {
        val data = ByteArray(100_000) { 1 }
        val result = readBytesLimited(ByteArrayInputStream(data), maxBytes = 2048)
        assertNull(result)
    }

    @Test
    fun emptyStream_returnsEmptyArray() {
        val result = readBytesLimited(ByteArrayInputStream(ByteArray(0)), maxBytes = 2048)
        assertEquals(0, result?.size)
    }

    @Test
    fun dripFeedStream_isReadCorrectly() {
        // Поток, отдающий по одному байту (как медленный облачный провайдер).
        val source = "hello attachment".toByteArray(Charsets.UTF_8)
        val drip = object : java.io.InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < source.size) source[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= source.size) return -1
                b[off] = source[pos++]
                return 1
            }
        }
        val result = readBytesLimited(drip, maxBytes = 1024)
        assertArrayEquals(source, result)
    }
}
