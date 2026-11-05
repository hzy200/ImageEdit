package com.example.imageedit.core.export

import android.content.Context
import org.json.JSONObject
import java.io.File

data class EditLogRecord(
    val instruction: String,
    val tools: List<String>,
    val models: List<String>,
    val width: Int,
    val height: Int,
    val format: String,
    val identifier: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Local-only JSONL edit log. Never uploaded.
 */
object LocalEditLog {
    private const val MAX_BYTES = 1_000_000L

    @Synchronized
    fun append(context: Context, record: EditLogRecord) {
        // Logging is best-effort: never fail an export because the log is full
        // or the filesystem is temporarily unavailable.
        runCatching {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val file = File(dir, "edit.log")
            if (file.exists() && file.length() > MAX_BYTES) {
                val backup = File(dir, "edit.log.1")
                backup.delete()
                file.renameTo(backup)
            }
            val json = JSONObject().apply {
                put("timestamp", record.timestamp)
                put("instruction", record.instruction)
                put("tools", org.json.JSONArray(record.tools))
                put("models", org.json.JSONArray(record.models))
                put("width", record.width)
                put("height", record.height)
                put("format", record.format)
                put("identifier", record.identifier)
            }
            file.appendText(json.toString() + "\n")
        }
    }

    @Synchronized
    fun readRecent(context: Context, limit: Int = 50): List<String> {
        return runCatching {
            val file = File(context.filesDir, "logs/edit.log")
            if (!file.exists()) emptyList() else file.readLines().takeLast(limit)
        }.getOrDefault(emptyList())
    }
}
