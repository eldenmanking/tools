package goals.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What this device opened by itself, kept in a file both processes read (see UnlockStore):
 * [openDay], the day an unlock code opened (yyyy-mm-dd, the sheet's date), and when the
 * allowance was last started. [wrongCodes] counts wrong codes in a row, the last at [lastWrong].
 */
@Serializable
data class Unlocks(
    val openDay: String? = null,
    val allowanceStart: Long? = null,
    val wrongCodes: Int = 0,
    val lastWrong: Long? = null,
)

object UnlocksJson {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun parse(text: String): Unlocks = try {
        json.decodeFromString(Unlocks.serializer(), text)
    } catch (e: SerializationException) {
        Unlocks()
    } catch (e: IllegalArgumentException) {
        Unlocks()
    }

    fun encode(u: Unlocks): String = json.encodeToString(Unlocks.serializer(), u)
}

/** Wrong codes allowed in a row before waiting [CODE_WAIT]. */
const val CODE_TRIES = 5
val CODE_WAIT: Duration = Duration.ofMinutes(15)

/**
 * The 6-digit code that opens [day] (yyyy-mm-dd): HMAC-SHA256 of "open:<day>" keyed by the
 * token, truncated as in HOTP (unlockCodeFrom in Core.js). The status page shows it once nothing
 * is left to gate that day.
 */
fun unlockCode(token: String, day: String): String {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(token.toByteArray(), "HmacSHA256")) }
    val b = mac.doFinal("open:$day".toByteArray())
    val o = b.last().toInt() and 0x0f
    val n = ((b[o].toInt() and 0x7f) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
        ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
    return (n % 1_000_000).toString().padStart(6, '0')
}

/** Until when codes can't be tried after too many wrong ones, or null. */
fun codeWaitUntil(u: Unlocks, now: Instant): Instant? {
    if (u.wrongCodes < CODE_TRIES) return null
    val until = Instant.ofEpochMilli(u.lastWrong ?: 0).plus(CODE_WAIT)
    return if (now.isBefore(until)) until else null
}

sealed interface CodeResult {
    data object Opened : CodeResult
    data class Wrong(val triesLeft: Int) : CodeResult
    data class Wait(val until: Instant) : CodeResult
}

/** Tries [code] for [day]; returns what it did and the unlocks to keep. */
fun tryCode(u: Unlocks, code: String, token: String, day: String, now: Instant): Pair<CodeResult, Unlocks> {
    codeWaitUntil(u, now)?.let { return CodeResult.Wait(it) to u }
    if (code == unlockCode(token, day)) return CodeResult.Opened to u.copy(openDay = day, wrongCodes = 0, lastWrong = null)
    // After a wait, a new round of tries.
    val wrong = (if (u.wrongCodes >= CODE_TRIES) 0 else u.wrongCodes) + 1
    val next = u.copy(wrongCodes = wrong, lastWrong = now.toEpochMilli())
    return (if (wrong >= CODE_TRIES) CodeResult.Wait(now.plus(CODE_WAIT)) else CodeResult.Wrong(CODE_TRIES - wrong)) to next
}
