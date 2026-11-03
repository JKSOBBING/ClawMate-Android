package com.clawmate.platform.sources

import org.junit.Assert.*
import org.junit.Test

class ImapTransportTest {
    @Test fun literalCountsBytesAndAllowsEmbeddedNewlines() {
        val body = "中\r\n文".toByteArray(Charsets.UTF_8)
        val prefix = "* 1 FETCH (BODY[] {${body.size}}\r\n".toByteArray()
        val frame = ImapTransport.readFrame((prefix + body + ")\r\nM1 OK done\r\n".toByteArray()).inputStream())
        assertArrayEquals(body, frame.substringAfter("\r\n").dropLast(1).toByteArray(Charsets.ISO_8859_1))
    }
    @Test fun rejectsOversizeAndIncompleteLiteral() {
        assertThrows(IllegalArgumentException::class.java) { ImapTransport.readFrame("* {1048577}\r\n".byteInputStream()) }
        assertThrows(IllegalStateException::class.java) { ImapTransport.readFrame("* {5}\r\nabc".byteInputStream()) }
    }
    @Test fun configurationCannotInjectCommands() {
        assertThrows(IllegalArgumentException::class.java) { ImapTransport.quoted("secret\r\nDELETE") }
        assertEquals("\"a\\\"b\\\\c\"", ImapTransport.quoted("a\"b\\c"))
    }
}
