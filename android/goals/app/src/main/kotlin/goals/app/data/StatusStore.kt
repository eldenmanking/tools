package goals.app.data

import android.content.Context
import goals.core.Status
import goals.core.StatusJson
import goals.core.StatusResult

/** The last status fetched, kept so the gate works offline (see goals.core.gateState). */
class StatusStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("status", Context.MODE_PRIVATE)

    fun load(): Status? = prefs.getString(KEY, null)?.let { (StatusJson.parse(it) as? StatusResult.Ok)?.status }

    fun save(status: Status) {
        prefs.edit().putString(KEY, StatusJson.encode(status)).apply()
    }

    private companion object {
        const val KEY = "last"
    }
}
