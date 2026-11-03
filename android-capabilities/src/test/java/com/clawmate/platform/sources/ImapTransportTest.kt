package com.clawmate.platform.sources

import org.junit.Assert.*
import org.junit.Test

class ImapTransportTest {
    @Test fun oauthSuccessUsesSaslAndFailureChallengeIsTerminatedWithoutLeakingDetails() {
        val sent = java.io.ByteArrayOutputStream()
        val success = ImapTransport("M1 OK authorized\r\n".byteInputStream(), sent)
        assertTrue(success.authenticate("test@example.com", "token").isEmpty())
        assertEquals("M1 AUTHENTICATE XOAUTH2 " + ImapTransport.oauthPayload("test@example.com", "token") + "\r\n", sent.toString("UTF-8"))
        sent.reset()
        val denied = ImapTransport("+ private-server-challenge\r\nM1 NO private-error\r\n".byteInputStream(), sent)
        val error = assertThrows(IllegalStateException::class.java) { denied.authenticate("test@example.com", "token") }
        assertTrue(sent.toString("UTF-8").endsWith("\r\n\r\n"))
        assertFalse(error.message!!.contains("private"))
        assertThrows(IllegalArgumentException::class.java) { success.command("AUTHENTICATE XOAUTH2 arbitrary") }
    }
    @Test fun oauthSaslUsesRequiredSeparatorsAndRejectsInjection() {
        val decoded = java.util.Base64.getDecoder().decode(ImapTransport.oauthPayload("test@example.com", "sample-token")).toString(Charsets.UTF_8)
        assertEquals("user=test@example.com\u0001auth=Bearer sample-token\u0001\u0001", decoded)
        assertThrows(IllegalArgumentException::class.java) { ImapTransport.oauthPayload("test\u0001@example.com", "token") }
        assertThrows(IllegalArgumentException::class.java) { ImapTransport.oauthPayload("test@example.com", "token\r\n") }
        assertThrows(IllegalArgumentException::class.java) { ImapTransport.oauthPayload("test@example.com", "") }
    }
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
