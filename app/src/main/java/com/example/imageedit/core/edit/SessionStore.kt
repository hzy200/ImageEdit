package com.example.imageedit.core.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.imageedit.core.tools.ToolInvocation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PersistedSession(
    val sourceUri: String?,
    val instruction: String,
    val invocations: List<ToolInvocation>,
    val updatedAt: Long,
)

/**
 * Persists the parameter history (never bitmaps) so a process death does not
 * lose edits. The last successful step is saved on every [EditSession.apply].
 */
class SessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "session/current.json")

    // Cache is excluded from backups. The snapshot lets the session restore
    // even when the original photo-picker URI grant has expired.
    private val previewFile = File(appContext.cacheDir, "session/preview.webp")

    @Synchronized
    fun save(
        sourceUri: String?,
        instruction: String,
        invocations: List<ToolInvocation>,
    ) {
        if (sourceUri.isNullOrBlank() || invocations.isEmpty()) {
            clearJson()
            return
        }
        runCatching {
            val root = JSONObject().apply {
                put("version", VERSION)
                put("source_uri", sourceUri)
                put("instruction", instruction)
                put("updated_at", System.currentTimeMillis())
                put(
                    "invocations",
                    JSONArray().apply {
                        invocations.forEach { invocation ->
                            put(
                                JSONObject().apply {
                                    put("tool", invocation.tool)
                                    put("args", argsToJson(invocation.args))
                                },
                            )
                        }
                    },
                )
            }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }.onFailure {
            Log.w(TAG, "session persist failed", it)
        }
    }

    @Synchronized
    fun load(): PersistedSession? = runCatching {
        if (!file.exists()) return null
        val root = JSONObject(file.readText())
        if (root.optInt("version", 0) != VERSION) return null
        val array = root.optJSONArray("invocations") ?: return null
        val invocations = mutableListOf<ToolInvocation>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val tool = item.optString("tool").takeIf { it.isNotBlank() } ?: continue
            invocations += ToolInvocation(
                tool = tool,
                args = jsonToArgs(item.optJSONObject("args")),
            )
        }
        if (invocations.isEmpty()) return null
        PersistedSession(
            sourceUri = root.optString("source_uri").takeIf { it.isNotBlank() },
            instruction = root.optString("instruction"),
            invocations = invocations,
            updatedAt = root.optLong("updated_at"),
        )
    }.onFailure {
        Log.w(TAG, "session restore failed", it)
    }.getOrNull()

    @Synchronized
    fun savePreview(bitmap: Bitmap) {
        runCatching {
            previewFile.parentFile?.mkdirs()
            val temp = File(previewFile.parentFile, previewFile.name + ".tmp")
            temp.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.WEBP, 90, output)) {
                    "preview snapshot encode failed"
                }
            }
            if (!temp.renameTo(previewFile)) {
                temp.copyTo(previewFile, overwrite = true)
                temp.delete()
            }
        }.onFailure {
            Log.w(TAG, "preview snapshot failed", it)
        }
    }

    @Synchronized
    fun loadPreview(): Bitmap? = runCatching {
        if (!previewFile.exists()) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        BitmapFactory.decodeFile(previewFile.absolutePath, options)
    }.onFailure {
        Log.w(TAG, "preview snapshot decode failed", it)
    }.getOrNull()

    @Synchronized
    fun clear() {
        clearJson()
        runCatching { previewFile.delete() }
    }

    private fun clearJson() {
        runCatching { file.delete() }
    }

    private fun argsToJson(args: Map<String, Any>): JSONObject = JSONObject().apply {
        args.forEach { (key, value) ->
            when (value) {
                is Boolean, is Int, is Long, is Float, is Double, is String -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private fun jsonToArgs(json: JSONObject?): Map<String, Any> {
        if (json == null) return emptyMap()
        val args = linkedMapOf<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.get(key)) {
                is Int, is Long, is Double, is Boolean -> args[key] = value
                else -> args[key] = value.toString()
            }
        }
        return args
    }

    private companion object {
        const val TAG = "SessionStore"
        const val VERSION = 1
    }
}
