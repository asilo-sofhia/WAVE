package com.example.wave.verify

import com.example.wave.model.QrResult
import com.google.crypto.tink.subtle.Ed25519Verify
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Base64

/**
 * Signed Wi-Fi QR codes, docs/CONTRACT.md §3. A step-by-step port of backend wave_sign.py `verify_qr`
 * (the reference), tested against backend/test-vectors/test_vectors.json. Pure: no Android.
 *
 * WIFI:T:WPA;S:<ssid>;P:<password>;WAVE-KID:<key>;WAVE-UNTIL:<yyyymmdd>;WAVE-SIG:<86 base64url chars>;;
 * Signed bytes: "WAVE-QR-v1\n" + the text from "WIFI:" up to and including the ';' before "WAVE-SIG:".
 */
object WifiQr {
    private val DOMAIN = "WAVE-QR-v1\n".toByteArray(Charsets.UTF_8)
    private const val SIG_MARKER = ";WAVE-SIG:"
    private const val SIG_LEN = 86 // 64 bytes in base64url, no padding
    private val SIG_TAIL = Regex("^[A-Za-z0-9_-]{$SIG_LEN};;$")
    private val FIELD_NAME = Regex("^[A-Z0-9-]+$")
    private val TYPES = setOf("WPA", "SAE", "nopass")
    private val UNTIL_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)

    /** [fields] are unescaped values (S, T, P, H, WAVE-KID, WAVE-UNTIL); empty when the text couldn't be read. */
    data class Check(val result: QrResult, val fields: Map<String, String> = emptyMap()) {
        val ssid: String? get() = fields["S"]
        val type: String? get() = fields["T"]
        val keyId: String? get() = fields["WAVE-KID"]
        val until: LocalDate? get() = fields["WAVE-UNTIL"]?.let { runCatching { LocalDate.parse(it, UNTIL_FORMAT) }.getOrNull() }
    }

    fun verify(raw: String, trustedKeys: Map<String, String> = WaveKeys.trusted, today: LocalDate = LocalDate.now()): Check {
        // 1. A WIFI: string ending in ";;".
        if (!raw.startsWith("WIFI:") || !raw.endsWith(";;")) return Check(QrResult.MALFORMED)

        // 2. Exactly one signature field (none = an ordinary, unsigned Wi-Fi QR).
        val count = raw.windowed(SIG_MARKER.length).count { it == SIG_MARKER }
        if (count == 0) return Check(QrResult.UNSIGNED, readFields(raw.removeSuffix(";").removePrefix("WIFI:")) ?: emptyMap())
        if (count > 1) return Check(QrResult.MALFORMED)

        // 3. The signature is the last field: 86 chars then ";;" and nothing else.
        val idx = raw.indexOf(SIG_MARKER)
        val tail = raw.substring(idx + SIG_MARKER.length)
        if (!SIG_TAIL.matches(tail)) return Check(QrResult.MALFORMED)
        val signedText = raw.substring(0, idx + 1)

        val list = parseFields(signedText.removePrefix("WIFI:")) ?: return Check(QrResult.MALFORMED)
        if (list.map { it.first }.toSet().size != list.size) return Check(QrResult.MALFORMED) // a field twice
        val fields = list.toMap()
        if (listOf("T", "S", "WAVE-KID", "WAVE-UNTIL").any { it !in fields }) return Check(QrResult.MALFORMED)
        if (fields.getValue("T") !in TYPES) return Check(QrResult.MALFORMED)
        val untilText = fields.getValue("WAVE-UNTIL")
        if (!Regex("^\\d{8}$").matches(untilText)) return Check(QrResult.MALFORMED)
        val until = try { LocalDate.parse(untilText, UNTIL_FORMAT) } catch (_: DateTimeParseException) { return Check(QrResult.MALFORMED) }

        // 4. A key the app has built in.
        val publicKey = trustedKeys[fields.getValue("WAVE-KID")]
            ?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
            ?: return Check(QrResult.UNKNOWN_KEY, fields)

        // 5. The signature.
        val signature = Base64.getUrlDecoder().decode(tail.substring(0, SIG_LEN))
        val genuine = try {
            Ed25519Verify(publicKey).verify(signature, DOMAIN + signedText.toByteArray(Charsets.UTF_8))
            true
        } catch (_: Exception) {
            false
        }
        if (!genuine) return Check(QrResult.BAD_SIGNATURE, fields)

        // 6. Expiry: WAVE-UNTIL is the last valid day (inclusive), compared with the device's local date.
        if (today.isAfter(until)) return Check(QrResult.EXPIRED, fields)
        return Check(QrResult.VALID, fields)
    }

    /** The QR text with the Wi-Fi password replaced, for showing on screen. */
    fun maskPassword(raw: String): String = Regex("(;P:)((?:\\\\.|[^;\\\\])*)(;)").replace(raw) { m ->
        m.groupValues[1] + "••••••••" + m.groupValues[3]
    }

    /** Fields of an unsigned code, best effort (null if it doesn't parse). */
    private fun readFields(body: String): Map<String, String>? = parseFields(body)?.toMap()

    /** "NAME:value;NAME:value;" with backslash escapes, exactly like wave_sign.py parse_qr_fields. Null = malformed. */
    private fun parseFields(body: String): List<Pair<String, String>>? {
        val fields = mutableListOf<Pair<String, String>>()
        val cur = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= body.length) return null // dangling backslash
                    cur.append('\\').append(body[i + 1])
                    i += 2
                    continue
                }
                c == ';' -> {
                    fields += splitField(cur.toString()) ?: return null
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        return if (cur.isEmpty()) fields else null // field not terminated by ';'
    }

    private fun splitField(raw: String): Pair<String, String>? {
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\\') { i += 2; continue }
            if (raw[i] == ':') {
                val name = raw.substring(0, i)
                if (name.isEmpty() || !FIELD_NAME.matches(name)) return null
                return name to (unescape(raw.substring(i + 1)) ?: return null)
            }
            i++
        }
        return null // field without ':'
    }

    private fun unescape(value: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            when (value[i]) {
                '\\' -> { out.append(value[i + 1]); i += 2 }
                ':', ';' -> return null // unescaped special character
                else -> { out.append(value[i]); i++ }
            }
        }
        return out.toString()
    }
}
