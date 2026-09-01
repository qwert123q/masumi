package rs.masumi.app.translation

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rs.masumi.core.serialization.TranslationJson
import rs.masumi.core.translation.TranslationModelResponse
import rs.masumi.core.translation.TranslationPromptMessages

class OpenAiCompatibleTranslationProvider(
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val translationJson: TranslationJson = TranslationJson(),
    private val responseJson: Json = Json { ignoreUnknownKeys = true },
    private val nanoTime: () -> Long = System::nanoTime,
) : TranslationProvider {
    override fun newCall(
        settings: TranslationProviderSettings,
        messages: TranslationPromptMessages,
    ): TranslationProviderCall = ProviderCall(settings, messages)

    private inner class ProviderCall(
        private val settings: TranslationProviderSettings,
        private val messages: TranslationPromptMessages,
    ) : TranslationProviderCall {
        private val cancelled = AtomicBoolean(false)
        private val executed = AtomicBoolean(false)
        private val activeCall = AtomicReference<Call?>()

        override fun cancel() {
            cancelled.set(true)
            activeCall.get()?.cancel()
        }

        override fun execute(): TranslationProviderResult {
            check(executed.compareAndSet(false, true)) { "translation provider calls are one-shot" }
            val started = nanoTime()
            val client = baseClient.newBuilder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(settings.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(settings.readTimeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(settings.writeTimeoutMillis, TimeUnit.MILLISECONDS)
                .build()
            val request = buildRequest(settings, messages)
            if (cancelled.get()) throw cancelled()
            try {
                val parsed = executeOnce(client, request)
                if (cancelled.get()) throw cancelled()
                return TranslationProviderResult(
                    response = parsed.response,
                    usage = parsed.usage,
                    modelId = parsed.modelId,
                    attemptCount = 1,
                    durationMillis = elapsedMillis(started),
                )
            } catch (error: TranslationProviderException) {
                throw error
            } catch (_: SocketTimeoutException) {
                throw TranslationProviderException(
                    code = TranslationProviderErrorCode.TIMEOUT,
                    attemptCount = 1,
                )
            } catch (_: IOException) {
                if (cancelled.get()) throw cancelled()
                throw TranslationProviderException(
                    code = TranslationProviderErrorCode.NETWORK,
                    attemptCount = 1,
                )
            }
        }

        private fun executeOnce(
            client: OkHttpClient,
            request: Request,
        ): ParsedResponse {
            val call = client.newCall(request)
            activeCall.set(call)
            try {
                call.execute().use { response ->
                    if (cancelled.get()) throw cancelled()
                    if (!response.isSuccessful) throw httpFailure(response)
                    val body = response.body?.string()
                        ?: throw malformed()
                    return parseResponse(body)
                }
            } finally {
                activeCall.compareAndSet(call, null)
            }
        }

        private fun parseResponse(body: String): ParsedResponse {
            try {
                val root = responseJson.parseToJsonElement(body).jsonObject
                val content = root["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive
                    ?.contentOrNull
                    ?: throw malformed()
                val structured = translationJson.decodeModelResponse(normalizeModelResponseContent(content))
                return ParsedResponse(
                    response = structured,
                    usage = parseUsage(root),
                    modelId = sanitizeModelId(root["model"]?.jsonPrimitive?.contentOrNull),
                )
            } catch (error: TranslationProviderException) {
                throw error
            } catch (_: SerializationException) {
                throw malformed()
            } catch (_: IllegalArgumentException) {
                throw malformed()
            }
        }

        private fun httpFailure(response: Response): TranslationProviderException {
            val transient = response.code == 408 || response.code == 429 || response.code in 500..599
            return TranslationProviderException(
                code = if (transient) {
                    TranslationProviderErrorCode.HTTP_TRANSIENT
                } else {
                    TranslationProviderErrorCode.HTTP_CLIENT
                },
                httpStatus = response.code,
                attemptCount = 1,
            )
        }

        private fun malformed() = TranslationProviderException(
            code = TranslationProviderErrorCode.MALFORMED_RESPONSE,
            attemptCount = 1,
        )

        private fun cancelled() = TranslationProviderException(
            code = TranslationProviderErrorCode.CANCELLED,
            attemptCount = 1,
        )

        private fun elapsedMillis(started: Long): Long =
            ((nanoTime() - started).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)

        private fun normalizeModelResponseContent(content: String): String {
            val parsed = responseJson.parseToJsonElement(content).jsonObject
            val rawGlossary = parsed["glossaryUpdates"] ?: return content
            if (rawGlossary is JsonObject) return content
            require(rawGlossary is JsonArray)
            val updates = linkedMapOf<String, String>()
            rawGlossary.forEach { element ->
                val entry = element.jsonObject
                val source = entry["source"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val translation = entry["translation"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                require(source.isNotBlank() && translation.isNotBlank() && source !in updates)
                updates[source] = translation
            }
            return buildJsonObject {
                parsed.forEach { (key, value) -> put(key, value) }
                put(
                    "glossaryUpdates",
                    buildJsonObject { updates.forEach { (source, translation) -> put(source, translation) } },
                )
            }.toString()
        }
    }

    private fun buildRequest(
        settings: TranslationProviderSettings,
        messages: TranslationPromptMessages,
    ): Request {
        val payload = buildJsonObject {
            put("model", settings.model)
            put("temperature", settings.temperature)
            put("max_tokens", settings.maximumOutputTokens)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", messages.system)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", messages.user)
                    })
                },
            )
            if (settings.requestJsonObjectFormat) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }
        return Request.Builder()
            .url(
                OpenAiCompatibleEndpointResolver.completionUrl(
                    settings.apiUrl,
                    settings.allowInsecureLocalhost,
                ),
            )
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("User-Agent", USER_AGENT)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun parseUsage(root: JsonObject): TranslationProviderUsage? {
        val usage = root["usage"]?.jsonObject ?: return null
        val prompt = usage["prompt_tokens"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0L }
        val completion = usage["completion_tokens"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0L }
        val total = usage["total_tokens"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0L }
        if (prompt == null && completion == null && total == null) return null
        return TranslationProviderUsage(prompt, completion, total ?: sumOrNull(prompt, completion))
    }

    private fun sumOrNull(first: Long?, second: Long?): Long? =
        if (first != null && second != null && Long.MAX_VALUE - first >= second) first + second else null

    private fun sanitizeModelId(value: String?): String? = value
        ?.takeIf { it.length in 1..128 && SAFE_MODEL_ID.matches(it) }

    private data class ParsedResponse(
        val response: TranslationModelResponse,
        val usage: TranslationProviderUsage?,
        val modelId: String?,
    )

    private companion object {
        const val USER_AGENT = "Masumi/0.1"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val SAFE_MODEL_ID = Regex("[A-Za-z0-9._:/-]+")
    }
}
