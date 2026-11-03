package com.clawmate.platform.sources

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Read-only IMAP transport. Literals are counted in octets, never Unicode characters. */
class ImapTransport : AutoCloseable {
    @Volatile private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var sequence = 0
    @Volatile private var closed = false
    fun open(host: String, port: Int) {
        require(host.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,251}[A-Za-z0-9]")) && port in 1..65535)
        check(!closed)
        val raw = Socket(); socket = raw
        raw.connect(InetSocketAddress(host, port), 15000)
        check(!closed)
        val tls = SSLSocketFactory.getDefault().let { it as SSLSocketFactory }
            .createSocket(raw, host, port, true) as SSLSocket
        socket = tls
        tls.soTimeout = 20000
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        tls.startHandshake(); check(!closed)
        input = BufferedInputStream(tls.inputStream)
        check(frame().startsWith("* OK")) { "目标不是IMAP服务" }
    }
    fun command(command: String): List<String> {
        check(!closed)
        require(!command.contains('\r') && !command.contains('\n') && command.length < 4096)
        require(command.startsWith("LOGIN ") || command == "CAPABILITY" || command.startsWith("ID ") ||
            command == "EXAMINE \"INBOX\"" || command.startsWith("UID SEARCH ") || command.startsWith("UID FETCH "))
        val tag = "M${++sequence}"
        socket!!.getOutputStream().apply { write(("$tag $command\r\n").toByteArray(Charsets.UTF_8)); flush() }
        val result = mutableListOf<String>(); var size = 0
        while (true) {
            check(!closed && !Thread.currentThread().isInterrupted)
            val line = frame(); size += line.length
            check(size <= 2 * 1024 * 1024 && result.size < 2000) { "邮件响应过大" }
            if (line.startsWith("$tag ")) {
                check(line.startsWith("$tag OK")) { "服务器拒绝请求，请核对IMAP授权码及设置" }
                return result
            }
            check(!line.startsWith("* BYE")) { "邮箱连接已关闭" }
            result.add(line)
        }
    }
    private fun frame(): String = readFrame(input ?: error("未连接"))
    override fun close() { closed = true; runCatching { socket?.close() }; socket = null }
    companion object {
        fun quoted(value: String): String {
            require(value.length in 1..1024 && value.none { it.code < 32 || it.code == 127 })
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        fun readFrame(input: java.io.InputStream): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val line = ByteArrayOutputStream(); var previous = -1
                while (true) {
                    val b = input.read(); check(b >= 0) { "连接提前关闭" }
                    line.write(b); check(out.size() + line.size() <= 2 * 1024 * 1024)
                    if (previous == 13 && b == 10) break
                    previous = b
                }
                val bytes = line.toByteArray(); out.write(bytes)
                val literal = Regex("\\{(\\d+)\\+?}\\r\\n$").find(bytes.toString(Charsets.ISO_8859_1))
                if (literal == null) return out.toByteArray().dropLast(2).toByteArray().toString(Charsets.ISO_8859_1)
                val count = literal.groupValues[1].toInt(); require(count <= 1024 * 1024 && out.size() + count <= 2 * 1024 * 1024)
                repeat(count) { val b = input.read(); check(b >= 0); out.write(b) }
            }
        }
    }
}
