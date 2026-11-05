package com.example.imageedit.core.intent

import android.content.Context
import android.util.Log
import com.example.imageedit.BuildConfig
import com.example.imageedit.core.contracts.CanvasDigest
import com.example.imageedit.core.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DeepSeek planner with a deterministic offline rule fallback. The API only
 * receives instruction + CanvasDigest text, never image bytes.
 */
class DeepSeekIntentGateway(
    context: Context,
    private val fallback: IntentGateway = RuleBasedIntentGateway,
) : IntentGateway {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    override suspend fun parse(instruction: String, digest: CanvasDigest): IntentResult =
        withContext(Dispatchers.IO) {
            val key = ApiKeyStore.get(appContext)
            if (key.isNullOrBlank()) {
                return@withContext fallback.parse(instruction, digest)
            }
            if (!NetworkStatus.isOnline(appContext)) {
                Log.i(TAG, "offline, using rule gateway")
                val offline = fallback.parse(instruction, digest)
                return@withContext offline.copy(message = offline.message ?: "当前离线，使用规则解析")
            }
            if (!configuredBaseUrl().startsWith("https://", ignoreCase = true)) {
                Log.w(TAG, "DEEPSEEK_BASE_URL must use https; refusing to send the API key")
                val offline = fallback.parse(instruction, digest)
                return@withContext offline.copy(
                    message = "云端地址必须使用 https，已切换离线规则",
                )
            }
            val remote = try {
                callApi(key, instruction, digest)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                Log.i(TAG, "remote call failed: ${error.message}")
                null
            }
            if (remote?.plan == null) {
                Log.i(TAG, "remote plan unavailable, using offline rules")
                val offline = fallback.parse(instruction, digest)
                IntentResult(
                    plan = offline.plan,
                    message = remote?.message ?: offline.message,
                    requiresSelection = offline.requiresSelection,
                )
            } else {
                Log.i(TAG, "remote plan parsed: ${remote.plan.steps.map { it.tool }}")
                remote
            }
        }

    private suspend fun callApi(
        key: String,
        instruction: String,
        digest: CanvasDigest,
    ): IntentResult {
        val system = """
            你是修图意图解析器。只输出 json，不要解释。
            你只能使用以下工具：${ToolRegistry.schemasAsJson()}
            输出格式：{"schema":"edit_plan/v1","understood":"...","steps":[{"id":"s1","tool":"...","args":{},"depends_on":[]}]}
            步数不超过 8；不要输出 estimated_ms 或 confirm_required。
        """.trimIndent()

        val user = "用户指令：$instruction\n画布摘要：${CanvasDigestJson.toJson(digest)}"
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", user))
        }
        val model = BuildConfig.DEEPSEEK_MODEL.ifBlank { "deepseek-flash" }
        val baseUrl = configuredBaseUrl()
        require(baseUrl.startsWith("https://", ignoreCase = true)) {
            "DEEPSEEK_BASE_URL 必须使用 https://，已拒绝发送 API Key"
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("max_tokens", 1024)
            put("temperature", 0.1)
            put("thinking", JSONObject().put("type", "disabled"))
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { result ->
                            val parsed = if (!result.isSuccessful) {
                                IntentResult(
                                    message = "云端解析失败（HTTP ${result.code}），已切换离线模板",
                                )
                            } else {
                                val body = result.body?.string().orEmpty()
                                val content = JSONObject(body)
                                    .getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .optString("content")
                                val plan = EditPlanParser.parse(content)
                                if (plan == null) {
                                    IntentResult(message = "云端计划格式不合法，已切换离线模板")
                                } else {
                                    IntentResult(plan = plan)
                                }
                            }
                            if (continuation.isActive) continuation.resume(parsed)
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    private fun configuredBaseUrl(): String =
        BuildConfig.DEEPSEEK_BASE_URL
            .ifBlank { "https://api.deepseek.com" }
            .trimEnd('/')

    private companion object {
        const val TAG = "DeepSeekIntentGateway"
    }
}
