package com.clawmate.app.log.domain

/**
 * Phase 14 — sensitive-value redaction for in-app log records.
 *
 * Log messages are treated as untrusted input that may include request payloads
 * or error text echoing an API key / token / secret. [redact] scrubs such values
 * *before* a record is allowed into the ring buffer, so a raw secret can never
 * be stored, rendered, or copied from the viewer. This is a deterministic,
 * pure function so it is fully unit-testable.
 *
 * Redactions applied (for exact patterns see the regexes):
 * - Authorization / proxy-authorization headers: `Bearer <token>`,
 *   `Basic <base64>`, and the whole `<scheme> <credential>` pair.
 * - `api_key=` / `apikey=` assignments and `sk-...` OpenAI-style keys.
 * - AWS-style credentials: `AccessKeyId`, `AWS_SECRET_ACCESS_KEY`, `AKIA...`.
 * - Generic `password=`, `secret=`, `token=`, `credential=`, `privatekey=`
 *   assignments; the key label is preserved and only the value is masked.
 *
 * Already-masked / blank values pass through untouched.
 */
object SensitiveLogRedactor {

    const val MASK: String = "[REDACTED]"

    private const val maxOffenderLength = 512

    /** Whole-value offenders, replaced by [MASK]. */
    private val wholeParameterPatterns = listOf(
        // Authorization: Bearer <token> / Basic <base64> — mask scheme + credential.
        Regex("""(?i)\b(authorization|proxy-authorization)\s*([:=])\s*([A-Za-z]+ )?\S+"""),
        Regex("(?i)\\bapi[_-]?key\\s*[:=]\\s*\\S+"),
        Regex("(?i)\\baws[_-]?secret[_-]?access[_-]?key\\s*[:=]\\s*\\S+"),
        Regex("(?i)\\baws[_-]?access[_-]?key[_-]?id\\s*[:=]\\s*\\S+"),
        Regex("\\bsk-[A-Za-z0-9_-]{8,}"),
        Regex("\\bghp_[A-Za-z0-9]{10,}"),
        Regex("\\bghs_[A-Za-z0-9]{10,}"),
        Regex("\\bAKIA[A-Z0-9]{12,}"),
    )

    /** Key/value offenders where the label is kept and the value masked. */
    private val kvPatterns = listOf(
        Regex("""(?i)\b(password|passwd|pwd|secret|token|access_token|refresh_token|credential|auth[a-z_]*|privatekey|private_key|session[_-]?key)\s*[:=]\s*("?[^",\s]+"?|'[^']*')"""),
    )

    fun redact(message: String): String {
        if (message.isBlank()) return message
        var out = message
        for (pattern in wholeParameterPatterns) {
            out = pattern.replace(out) { mr ->
                if (mr.value.length <= maxOffenderLength) MASK else MASK
            }
        }
        for (pattern in kvPatterns) {
            out = pattern.replace(out) { mr ->
                mr.groupValues[1] + "=" + MASK
            }
        }
        return out
    }
}
