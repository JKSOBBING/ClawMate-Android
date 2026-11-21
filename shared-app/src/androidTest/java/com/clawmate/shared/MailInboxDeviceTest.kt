package com.clawmate.shared

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.sources.PersonalSourceEngine
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

class MailInboxDeviceTest {
    @Test fun fetchesReadOnlyOnVerifiedLocalTlsAndRejectsBadCredentials() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "imap-fixture-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences(directory.name + name, mode)
        }
        val identity = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(identity).build().sslContext()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(identity.certificate).build().sslContext()
        val previous = SSLContext.getDefault()
        val executor = Executors.newSingleThreadExecutor()
        val body = "ClawMate synthetic inbox receipt"
        val host = "127.0.0.1"
        try {
            SSLContext.setDefault(clientTls)
            serverTls.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName(host)).use { server ->
                server.soTimeout = 15000
                val serve = executor.submit {
                    repeat(2) { attempt ->
                        server.accept().use { socket ->
                            socket.soTimeout = 15000
                            val input = socket.getInputStream().bufferedReader()
                            val output = socket.getOutputStream().bufferedWriter()
                            fun send(line: String) { output.write(line + "\r\n"); output.flush() }
                            send("* OK synthetic inbox")
                            val login = input.readLine()
                            if (attempt == 1) {
                                check(login == "M1 LOGIN \"qa@example.invalid\" \"reject\"")
                                send("M1 NO authentication failed")
                            } else {
                                check(login == "M1 LOGIN \"qa@example.invalid\" \"allow\""); send("M1 OK")
                                check(input.readLine() == "M2 EXAMINE \"INBOX\"")
                                send("* OK [UIDVALIDITY 42]"); send("M2 OK")
                                check(input.readLine().startsWith("M3 UID SEARCH SINCE "))
                                send("* SEARCH 7"); send("M3 OK")
                                check(input.readLine() == "M4 UID FETCH 7 (UID BODY.PEEK[1]<0.4096>)")
                                send("* 1 FETCH (UID 7 BODY[1]<0> {${body.length}}")
                                send(body + ")"); send("M4 OK")
                            }
                        }
                    }
                }
                PersonalSourceEngine(isolated).use { engine ->
                    fun connect(secret: String) {
                        engine.saveCredentials(host, server.localPort, "qa@example.invalid", secret)
                        engine.saveOptions("mail", """{"mailEnabled":true,"maxMessages":20}""")
                        engine.execute("mailOpen", "")
                    }
                    connect("allow")
                    fun command(value: String) = JSONArray(engine.execute("mailCommand", value))
                    command("@LOGIN")
                    assertTrue(command("EXAMINE \"INBOX\"").getString(0).contains("UIDVALIDITY 42"))
                    val since = java.time.LocalDate.now().minusDays(2).format(
                        java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.US))
                    assertEquals("* SEARCH 7", command("UID SEARCH SINCE $since").getString(0))
                    assertTrue(command("UID FETCH 7 (UID BODY.PEEK[1]<0.4096>)").getString(0).contains(body))
                    connect("reject")
                    assertThrows(IllegalStateException::class.java) { command("@LOGIN") }
                }
                serve.get(10, TimeUnit.SECONDS)
            }
        } finally {
            SSLContext.setDefault(previous)
            executor.shutdownNow()
            base.deleteSharedPreferences(directory.name + "personal_sources")
            base.deleteSharedPreferences(directory.name + "personal_source_options")
            directory.deleteRecursively()
        }
    }
}
