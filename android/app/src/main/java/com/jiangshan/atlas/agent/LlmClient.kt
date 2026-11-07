package com.jiangshan.atlas.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容的 chat completions 客户端。
 *
 * 覆盖 DeepSeek / MiniMax / Kimi / 通义 / OpenAI 以及任何自建网关——
 * 它们都提供 `POST {base}/v1/chat/completions` 这条路径。
 */
object LlmClient {

    /** 一次问答的原始结果：正常文本，或者可读的错误说明 */
    data class Result(val ok: Boolean, val text: String)

    fun chat(config: LlmConfig, systemPrompt: String, userPrompt: String): Result {
        if (!config.isUsable()) {
            return Result(false, "还没配置模型：请选择厂商并填入 API Key")
        }

        val payload = JSONObject().apply {
            put("model", config.model)
            put("temperature", config.temperature)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(config.endpoint()).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                setRequestProperty("Accept", "application/json")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload.toString())
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val body = readAll(conn.errorStream)
                return Result(false, describeHttpError(code, body))
            }

            val body = readAll(conn.inputStream)
            val text = extractContent(body)
            if (text.isBlank()) Result(false, "模型返回为空") else Result(true, text)
        } catch (e: Exception) {
            Result(false, "请求失败：${e.javaClass.simpleName} ${e.message ?: ""}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 从 OpenAI 响应里取 choices[0].message.content */
    private fun extractContent(body: String): String {
        return try {
            val root = JSONObject(body)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) return ""
            val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
            msg.optString("content", "")
        } catch (e: Exception) {
            ""
        }
    }

    /** 尽量把厂商的错误信息原样带出来，方便用户判断是额度、Key 还是模型名的问题 */
    private fun describeHttpError(code: Int, body: String): String {
        val detail = try {
            val root = JSONObject(body)
            val err = root.optJSONObject("error")
            when {
                err != null -> err.optString("message", "").ifEmpty { body }
                root.has("base_resp") -> root.optJSONObject("base_resp")?.optString("status_msg", "") ?: body
                else -> body
            }
        } catch (e: Exception) {
            body
        }
        val hint = when (code) {
            401, 403 -> "（API Key 无效或无权限）"
            404 -> "（模型名不存在，检查「模型」字段）"
            429 -> "（触发限流或额度用尽）"
            in 500..599 -> "（服务端错误，稍后重试）"
            else -> ""
        }
        return "HTTP $code $hint\n$detail".trim()
    }

    private fun readAll(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 从模型输出里抠出第一个 JSON 对象。
     * 模型偶尔会带 ``` 围栏或前后废话，这里做宽容解析。
     */
    fun extractJson(text: String): JSONObject? {
        val cleaned = text.trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")

        try {
            return JSONObject(cleaned)
        } catch (_: Exception) {
            // 继续尝试从文本里抠
        }

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        return try {
            JSONObject(cleaned.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }
}
