package goals.app.data

import android.content.Context
import android.util.AtomicFile
import goals.core.Status
import goals.core.StatusJson
import goals.core.StatusResult
import java.io.File
import java.io.IOException

/**
 * The last status fetched, kept so the gate works offline (see goals.core.gateState).
 * A file, not SharedPreferences, because two processes use it: the UI process writes it
 * after each check and the :watch process reads it. SharedPreferences caches per process
 * and wouldn't see the other's writes. Writes are atomic; [load] re-reads only when the
 * file changed, so polling it is cheap.
 */
class StatusStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "status.json"))
    private var loadedStamp = Long.MIN_VALUE
    private var loaded: Status? = null

    @Synchronized
    fun load(): Status? {
        val base = file.baseFile
        val stamp = if (base.exists()) base.lastModified() xor base.length() else -1L
        if (stamp != loadedStamp) {
            loaded = try {
                (StatusJson.parse(file.readFully().decodeToString()) as? StatusResult.Ok)?.status
            } catch (e: IOException) {
                null
            }
            loadedStamp = stamp
        }
        return loaded
    }

    @Synchronized
    fun save(status: Status) {
        val out = file.startWrite()
        try {
            out.write(StatusJson.encode(status).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
        }
    }
}
