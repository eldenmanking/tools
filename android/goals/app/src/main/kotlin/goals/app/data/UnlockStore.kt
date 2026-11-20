package goals.app.data

import android.content.Context
import android.util.AtomicFile
import goals.core.Unlocks
import goals.core.UnlocksJson
import java.io.File
import java.io.IOException

/**
 * What this device opened by itself (the allowance, an unlock code): written by the UI process,
 * read by :watch on every poll. A file for the same reasons as [StatusStore].
 */
class UnlockStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "unlocks.json"))
    private var loadedStamp = Long.MIN_VALUE
    private var loaded = Unlocks()

    @Synchronized
    fun load(): Unlocks {
        val base = file.baseFile
        val stamp = if (base.exists()) base.lastModified() xor base.length() else -1L
        if (stamp != loadedStamp) {
            loaded = try { UnlocksJson.parse(file.readFully().decodeToString()) } catch (e: IOException) { Unlocks() }
            loadedStamp = stamp
        }
        return loaded
    }

    @Synchronized
    fun save(u: Unlocks) {
        val out = file.startWrite()
        try {
            out.write(UnlocksJson.encode(u).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
        }
    }
}
