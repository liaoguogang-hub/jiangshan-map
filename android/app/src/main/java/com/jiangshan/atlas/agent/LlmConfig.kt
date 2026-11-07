package com.jiangshan.atlas.agent

import android.content.Context
import android.content.SharedPreferences

/**
 * 模型服务配置。
 *
 * 全部按 OpenAI 兼容格式（`POST {baseUrl}/v1/chat/completions`）调用，
 * 主流厂商现在都提供这条兼容路径，所以一套客户端就能覆盖。
 */
data class LlmConfig(
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double = 0.0,
) {
    fun endpoint(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/v1")) "$base/chat/completions"
        else "$base/v1/chat/completions"
    }

    fun isUsable(): Boolean = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        /**
         * 预设厂商。用户只需选厂商 + 填 Key，地址和模型名自动带出。
         * 都可以在界面上改，所以「自定义」也能覆盖任何自建网关。
         */
        val PRESETS = listOf(
            LlmConfig(
                providerId = "deepseek",
                baseUrl = "https://api.deepseek.com",
                apiKey = "",
                model = "deepseek-chat",
            ),
            LlmConfig(
                providerId = "minimax",
                baseUrl = "https://api.minimaxi.com",
                apiKey = "",
                model = "MiniMax-M3.1-Flash",
            ),
            LlmConfig(
                providerId = "kimi",
                baseUrl = "https://api.moonshot.cn/v1",
                apiKey = "",
                model = "moonshot-v1-8k",
            ),
            LlmConfig(
                providerId = "qwen",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKey = "",
                model = "qwen-plus",
            ),
            LlmConfig(
                providerId = "openai",
                baseUrl = "https://api.openai.com/v1",
                apiKey = "",
                model = "gpt-4o-mini",
            ),
            LlmConfig(
                providerId = "custom",
                baseUrl = "",
                apiKey = "",
                model = "",
            ),
        )

        fun preset(id: String): LlmConfig = PRESETS.firstOrNull { it.providerId == id } ?: PRESETS.first()

        fun displayName(id: String): String = when (id) {
            "deepseek" -> "DeepSeek"
            "minimax" -> "MiniMax"
            "kimi" -> "Kimi (Moonshot)"
            "qwen" -> "通义千问"
            "openai" -> "OpenAI"
            else -> "自定义"
        }

        fun presetIds(): List<String> = PRESETS.map { it.providerId }
    }
}

/** 按厂商分别保存配置，切换厂商不丢已填的 Key */
class LlmConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var activeProvider: String
        get() = prefs.getString(KEY_ACTIVE, LlmConfig.PRESETS.first().providerId).orEmpty()
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    fun load(providerId: String = activeProvider): LlmConfig {
        val preset = LlmConfig.preset(providerId)
        return preset.copy(
            baseUrl = prefs.getString("${providerId}_base", preset.baseUrl).orEmpty(),
            apiKey = prefs.getString("${providerId}_key", "").orEmpty(),
            model = prefs.getString("${providerId}_model", preset.model).orEmpty(),
        )
    }

    fun save(config: LlmConfig) {
        prefs.edit()
            .putString("${config.providerId}_base", config.baseUrl)
            .putString("${config.providerId}_key", config.apiKey)
            .putString("${config.providerId}_model", config.model)
            .putString(KEY_ACTIVE, config.providerId)
            .apply()
    }

    companion object {
        private const val PREFS = "jiangshan_agent_llm"
        private const val KEY_ACTIVE = "active_provider"
    }
}
