package com.example.imageedit.core.ml

import android.content.Context
import com.example.imageedit.core.AppGraph
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ModelHandle(
    val label: String,
    val file: File?,
    val opener: () -> InputStream,
)

data class ModelStatus(
    val available: Boolean,
    val verified: Boolean,
    val activePath: String?,
    val message: String,
)

class ModelIntegrityException(message: String) : IllegalStateException(message)

/**
 * Model source/version manager. Bundled assets are the default; a downloaded
 * file can override an asset, and rollback restores the previous active file
 * (or the bundled asset when there is none).
 */
object ModelManager {
    private const val PREFS = "imageedit_models"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun context(): Context = AppGraph.appContext
    private fun prefs() = context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun open(manifest: ModelManifest): ModelHandle {
        val activePath = prefs().getString(activeKey(manifest), null)
        val activeFile = activePath?.let(::File)
        if (activeFile != null && activeFile.exists()) {
            verifyFile(manifest, activeFile)
            return ModelHandle(
                label = "file:${activeFile.name}",
                file = activeFile,
                opener = { activeFile.inputStream() },
            )
        }

        verifyAsset(manifest)
        return ModelHandle(
            label = "asset:${manifest.assetPath}",
            file = null,
            opener = { context().assets.open(manifest.assetPath) },
        )
    }

    @Synchronized
    fun downloadAndInstall(manifest: ModelManifest, url: String): ModelHandle {
        val target = fileFor(manifest)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".download")
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ModelIntegrityException("模型下载失败：HTTP ${response.code}")
                }
                val body = response.body ?: throw ModelIntegrityException("模型下载失败：空响应")
                temp.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
            verifyFileBytes(manifest, temp)
            val previous = prefs().getString(activeKey(manifest), null)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            if (previous != null && previous != target.absolutePath) {
                prefs().edit().putString(previousKey(manifest), previous).apply()
            }
            prefs().edit()
                .putString(activeKey(manifest), target.absolutePath)
                .putBoolean(verifiedKey(manifest), true)
                .apply()
            return ModelHandle("file:${target.name}", target) { target.inputStream() }
        } catch (error: Throwable) {
            temp.delete()
            if (error is ModelIntegrityException) throw error
            throw ModelIntegrityException("模型下载/校验失败：${error.message}")
        }
    }

    @Synchronized
    fun rollback(manifest: ModelManifest): ModelStatus {
        val previous = prefs().getString(previousKey(manifest), null)
        if (previous != null) {
            val file = File(previous)
            if (file.exists()) {
                prefs().edit().putString(activeKey(manifest), file.absolutePath).apply()
                return status(manifest)
            }
        }
        // No previous file: fall back to the bundled asset.
        prefs().edit().remove(activeKey(manifest)).apply()
        return status(manifest)
    }

    fun status(manifest: ModelManifest): ModelStatus {
        val activePath = prefs().getString(activeKey(manifest), null)
        val activeFile = activePath?.let(::File)
        if (activeFile != null && activeFile.exists()) {
            return ModelStatus(
                available = true,
                verified = prefs().getBoolean(verifiedKey(manifest), false),
                activePath = activeFile.absolutePath,
                message = "使用已下载模型",
            )
        }
        return ModelStatus(
            available = true,
            verified = prefs().getBoolean(assetVerifiedKey(manifest), false),
            activePath = "asset:${manifest.assetPath}",
            message = "使用随包模型",
        )
    }

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    private fun verifyAsset(manifest: ModelManifest) {
        val key = assetVerifiedKey(manifest)
        if (prefs().getBoolean(key, false)) return
        val digest = context().assets.open(manifest.assetPath).use { sha256(it) }
        if (!digest.equals(manifest.sha256, ignoreCase = true)) {
            throw ModelIntegrityException("随包模型校验失败：${manifest.name}")
        }
        prefs().edit().putBoolean(key, true).apply()
    }

    private fun verifyFile(manifest: ModelManifest, file: File) {
        val key = verifiedKey(manifest)
        if (prefs().getBoolean(key, false)) return
        verifyFileBytes(manifest, file)
        prefs().edit().putBoolean(key, true).apply()
    }

    private fun verifyFileBytes(manifest: ModelManifest, file: File) {
        val digest = sha256(file)
        if (!digest.equals(manifest.sha256, ignoreCase = true)) {
            throw ModelIntegrityException("模型校验失败：${manifest.name}")
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun fileFor(manifest: ModelManifest): File {
        val ext = manifest.assetPath.substringAfterLast('.', "onnx")
        val safeName = manifest.name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val safeVersion = manifest.version.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(context().filesDir, "models/$safeName/$safeVersion.$ext")
    }

    private fun activeKey(manifest: ModelManifest) = "active:${manifest.name}"
    private fun previousKey(manifest: ModelManifest) = "previous:${manifest.name}"
    private fun verifiedKey(manifest: ModelManifest) =
        "verified:${manifest.name}:${manifest.version}:${manifest.sha256}"

    private fun assetVerifiedKey(manifest: ModelManifest) =
        "verified:asset:${manifest.name}:${manifest.version}:${manifest.sha256}"
}
