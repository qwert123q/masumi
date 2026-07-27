package rs.masumi.app.translation

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class TranslationEndpointPreset(
    val name: String,
    val apiUrl: String?,
) {
    override fun toString(): String = apiUrl?.let { "$name · $it" } ?: name
}

internal object TranslationProviderCatalog {
    val commonEndpoints = listOf(
        TranslationEndpointPreset("OpenAI", "https://api.openai.com/v1"),
        TranslationEndpointPreset("DeepSeek", "https://api.deepseek.com"),
        TranslationEndpointPreset("OpenRouter", "https://openrouter.ai/api/v1"),
        TranslationEndpointPreset("SiliconFlow", "https://api.siliconflow.cn/v1"),
        TranslationEndpointPreset("Groq", "https://api.groq.com/openai/v1"),
        TranslationEndpointPreset(
            "Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai",
        ),
    )

    fun suggestedName(apiUrl: String): String {
        commonEndpoints.firstOrNull { it.apiUrl?.trimEnd('/') == apiUrl.trim().trimEnd('/') }
            ?.let { return it.name }
        val host = apiUrl.trim().toHttpUrlOrNull()?.host.orEmpty()
        return host.takeIf(String::isNotBlank) ?: "自定义服务"
    }
}
