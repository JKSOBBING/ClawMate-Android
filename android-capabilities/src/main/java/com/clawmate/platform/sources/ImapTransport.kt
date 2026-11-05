package com.clawmate.platform.sources

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Read-only IMAP transport. Literals are counted in octets, never Unicode characters. */
class ImapTransport() : AutoCloseable {
    @Volatile private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: java.io.OutputStream? = null
    internal constructor(input: java.io.InputStream, output: java.io.OutputStream) : this() {
        this.input = BufferedInputStream(input); this.output = output
    }
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
        output = tls.outputStream
        check(frame().startsWith("* OK")) { "目标不是IMAP服务" }
    }
    fun command(command: String): List<String> {
        return exchange(command, false)
    }
    fun authenticate(user: String, token: String): List<String> = exchange("AUTHENTICATE XOAUTH2 " + oauthPayload(user, token), true)
    /** RFC 7628（OAUTHBEARER）认证，jAccount 授权令牌经此登录 IMAP。 */
    fun authenticateBearer(user: String, token: String, host: String = "", port: Int = 0): List<String> =
        exchange("AUTHENTICATE OAUTHBEARER " + bearerPayload(user, token, host, port), true)
    private fun exchange(command: String, oauth: Boolean): List<String> {
        check(!closed)
        require(!command.contains('\r') && !command.contains('\n') && command.length < 32768)
        require(oauth || command.startsWith("LOGIN ") || command == "CAPABILITY" || command.startsWith("ID ") ||
            command == "EXAMINE \"INBOX\"" || command.startsWith("UID SEARCH ") || command.startsWith("UID FETCH "))
        val tag = "M${++sequence}"
        (output ?: error("未连接")).apply { write(("$tag $command\r\n").toByteArray(Charsets.UTF_8)); flush() }
        val result = mutableListOf<String>(); var size = 0; var challenged = false
        while (true) {
            check(!closed && !Thread.currentThread().isInterrupted)
            val line = frame(); size += line.length
            if (oauth && line.startsWith("+")) {
                check(!challenged); challenged = true
                output!!.apply { write("\r\n".toByteArray()); flush() }
                continue
            }
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
        /** IMAP 字面量标记（`{n}` 或 `{n+}`）的预编译模式；右花括号必须转义。 */
        val literalPattern = Regex("\\{(\\d+)\\+?\\}\\r\\n$")

        fun oauthPayload(user: String, token: String): String {
            quoted(user)
            require(token.length in 1..16000 && token.none { it.code <= 32 || it.code == 127 })
            return java.util.Base64.getEncoder().encodeToString("user=$user\u0001auth=Bearer $token\u0001\u0001".toByteArray(Charsets.UTF_8))
        }
        /** RFC 7628 OAUTHBEARER 初始响应：`n,a=<user>,^Ahost=<host>^Aport=<port>^Aauth=Bearer <token>^A^A`。 */
        fun bearerPayload(user: String, token: String, host: String = "", port: Int = 0): String {
            quoted(user)
            require(token.length in 1..16000 && token.none { it.code <= 32 || it.code == 127 })
            val hostPart = if (host.isEmpty()) "" else "host=${host}\u0001port=$port\u0001"
            return java.util.Base64.getEncoder().encodeToString(("n,a=$user,\u0001$hostPart" + "auth=Bearer $token\u0001\u0001").toByteArray(Charsets.UTF_8))
        }
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
                // 字面量标记形如 `{123}` 或 `{123+}`；右花括号在 Java 正则里必须转义，否则会抛
                // PatternSyntaxException，导致所有 IMAP 读取失败。
                val literal = literalPattern.find(bytes.toString(Charsets.ISO_8859_1))
                if (literal == null) return out.toByteArray().dropLast(2).toByteArray().toString(Charsets.ISO_8859_1)
                val count = literal.groupValues[1].toInt(); require(count <= 1024 * 1024 && out.size() + count <= 2 * 1024 * 1024)
                repeat(count) { val b = input.read(); check(b >= 0); out.write(b) }
            }
        }
    }
}
