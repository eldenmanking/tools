package goals.app.data

import android.util.Log
import goals.app.BuildConfig
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
 * with a redirect to the result, which HttpURLConnection follows (https to https).
 */
class HttpStatusSource(private val url: String, private val token: String) : StatusSource {
    override suspend fun fetch(): StatusResult = withContext(Dispatchers.IO) {
        val conn = URL("$url?token=${URLEncoder.encode(token, "UTF-8")}").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return@withContext StatusResult.Error("server answered $code")
            StatusJson.parse(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: IOException) {
            StatusResult.Error(e.message ?: "no connection")
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}

/** The status source this build uses: the endpoint from local.properties, else the sample. */
object StatusSources {
    val default: StatusSource by lazy {
        if (BuildConfig.GATE_URL.isNotBlank() && BuildConfig.GATE_TOKEN.isNotBlank()) {
            HttpStatusSource(BuildConfig.GATE_URL, BuildConfig.GATE_TOKEN)
        } else {
            Log.w("GymGate", "gate.url or gate.token missing from local.properties; using sample data")
            SampleStatusSource
        }
    }
}
