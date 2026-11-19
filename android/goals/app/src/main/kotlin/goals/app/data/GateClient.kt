package goals.app.data

import android.util.Log
import goals.app.BuildConfig
import goals.core.SaveResult
import goals.core.ScheduleJson
import goals.core.ScheduleResult
import goals.core.StatusJson
import goals.core.StatusResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The Apps Script web app at [url] (its /exec URL), authorized by [token]. Apps Script answers
 * every request with a redirect to the result, fetched with a GET; redirects are followed here
 * rather than by HttpURLConnection, which may not turn a redirected POST into a GET.
 */
class GateClient(private val url: String, private val token: String) : StatusSource {
    override suspend fun fetch(): StatusResult =
        request(null, null).fold({ StatusJson.parse(it) }, { StatusResult.Error(it) })

    suspend fun schedule(): ScheduleResult =
        request("schedule", null).fold({ ScheduleJson.parse(it) }, { ScheduleResult.Error(it) })

    /** Sends a save request ([ScheduleJson] builds the body). */
    suspend fun save(body: String): SaveResult =
        request(null, body).fold({ ScheduleJson.parseSave(it) }, { SaveResult.Error(it) })

    /** The response body, or an error message. A [body] makes it a POST. */
    private suspend fun request(action: String?, body: String?): Answer = withContext(Dispatchers.IO) {
        var target = "$url?token=${URLEncoder.encode(token, "UTF-8")}" + (action?.let { "&action=$it" } ?: "")
        var post = body
        repeat(MAX_HOPS) {
            val conn = URL(target).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                if (post != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(post!!.toByteArray()) }
                }
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> return@withContext Answer(conn.inputStream.bufferedReader().use { it.readText() }, null)
                    in 301..303, 307, 308 -> {
                        target = conn.getHeaderField("Location") ?: return@withContext Answer(null, "redirect without a location")
                        post = null // the result is fetched with a GET
                    }
                    else -> return@withContext Answer(null, "server answered $code")
                }
            } catch (e: IOException) {
                return@withContext Answer(null, e.message ?: "no connection")
            } finally {
                conn.disconnect()
            }
        }
        Answer(null, "too many redirects")
    }

    private class Answer(val text: String?, val error: String?) {
        fun <T> fold(ok: (String) -> T, failed: (String) -> T): T = if (text != null) ok(text) else failed(error ?: "no answer")
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val MAX_HOPS = 4
    }
}

/** What this build talks to: the endpoint from local.properties, else the sample. */
object StatusSources {
    /** Null when local.properties names no endpoint. */
    val client: GateClient? by lazy {
        if (BuildConfig.GATE_URL.isNotBlank() && BuildConfig.GATE_TOKEN.isNotBlank()) {
            GateClient(BuildConfig.GATE_URL, BuildConfig.GATE_TOKEN)
        } else {
            Log.w("GymGate", "gate.url or gate.token missing from local.properties; using sample data")
            null
        }
    }

    val default: StatusSource get() = client ?: SampleStatusSource
}
