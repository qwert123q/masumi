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
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import rs.masumi.core.serialization.TranslationJson
import rs.masumi.core.translation.TranslationModelResponse
import rs.masumi.core.translation.TranslationPromptMessages

class OpenAiCompatibleTranslationProvider(
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val translationJson: TranslationJson = TranslationJson(),
    private val responseJson: Json = Json { ignoreUnknownKeys = true },
    private val retryWaiter: TranslationRetryWaiter = TranslationRetryWaiter(::waitWithCancellation),
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
                .connectTimeout(settings.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(settings.readTimeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(settings.writeTimeoutMillis, TimeUnit.MILLISECONDS)
                .build()
            val request = buildRequest(settings, messages)
            var attempt = 0
            while (attempt < settings.maximumAttempts) {
                attempt += 1
                if (cancelled.get()) throw cancelled(attempt)
                try {
                    val parsed = executeOnce(client, request, attempt)
                    if (cancelled.get()) throw cancelled(attempt)
                    return TranslationProviderResult(
                        response = parsed.response,
                        usage = parsed.usage,
                        modelId = parsed.modelId,
                        attemptCount = attempt,
                        durationMillis = elapsedMillis(started),
                    )
                } catch (error: TranslationProviderException) {
                    if (!error.retryable || attempt >= settings.maximumAttempts) throw error
                    if (!retryWaiter.wait(settings.retryDelayMillis, cancelled::get)) {
                        throw cancelled(attempt)
                    }
                } catch (_: SocketTimeoutException) {
                    val mapped = TranslationProviderException(
                        code = TranslationProviderErrorCode.TIMEOUT,
                        retryable = true,
                        attemptCount = attempt,
                    )
                    if (attempt >= settings.maximumAttempts) throw mapped
                    if (!retryWaiter.wait(settings.retryDelayMillis, cancelled::get)) {
                        throw cancelled(attempt)
                    }
                } catch (_: IOException) {
                    if (cancelled.get()) throw cancelled(attempt)
                    val mapped = TranslationProviderException(
                        code = TranslationProviderErrorCode.NETWORK,
                        retryable = true,
                        attemptCount = attempt,
                    )
                    if (attempt >= settings.maximumAttempts) throw mapped
                    if (!retryWaiter.wait(settings.retryDelayMillis, cancelled::get)) {
                        throw cancelled(attempt)
                    }
                }
            }
            error("attempt loop must return or throw")
        }

        private fun executeOnce(
            client: OkHttpClient,
            request: Request,
            attempt: Int,
        ): ParsedResponse {
            val call = client.newCall(request)
            activeCall.set(call)
            try {
                call.execute().use { response ->
                    if (cancelled.get()) throw cancelled(attempt)
                    if (!response.isSuccessful) throw httpFailure(response, attempt)
                    val body = response.body?.string()
                        ?: throw malformed(attempt)
                    return parseResponse(body, attempt)
                }
            } finally {
                activeCall.compareAndSet(call, null)
            }
        }

        private fun parseResponse(body: String, attempt: Int): ParsedResponse {
            try {
                val root = responseJson.parseToJsonElement(body).jsonObject
                val content = root["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive
                    ?.contentOrNull
                    ?: throw malformed(attempt)
                val structured = translationJson.decodeModelResponse(normalizeModelResponseContent(content))
                return ParsedResponse(
                    response = structured,
                    usage = parseUsage(root),
                    modelId = sanitizeModelId(root["model"]?.jsonPrimitive?.contentOrNull),
                )
            } catch (error: TranslationProviderException) {
                throw error
            } catch (_: SerializationException) {
                throw malformed(attempt)
            } catch (_: IllegalArgumentException) {
                throw malformed(attempt)
            }
        }

        private fun httpFailure(response: Response, attempt: Int): TranslationProviderException {
            val transient = response.code == 408 || response.code == 429 || response.code in 500..599
            return TranslationProviderException(
                code = if (transient) {
                    TranslationProviderErrorCode.HTTP_TRANSIENT
                } else {
                    TranslationProviderErrorCode.HTTP_CLIENT
                },
                httpStatus = response.code,
                retryable = transient,
                attemptCount = attempt,
            )
        }

        private fun malformed(attempt: Int) = TranslationProviderException(
            code = TranslationProviderErrorCode.MALFORMED_RESPONSE,
            retryable = true,
            attemptCount = attempt,
        )

        private fun cancelled(attempt: Int) = TranslationProviderException(
            code = TranslationProviderErrorCode.CANCELLED,
            retryable = false,
            attemptCount = attempt,
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
            .url(completionUrl(settings))
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("User-Agent", USER_AGENT)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun completionUrl(settings: TranslationProviderSettings): HttpUrl {
        val parsed = settings.apiUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("apiUrl is invalid")
        require(parsed.query == null && parsed.fragment == null) { "apiUrl must not contain query or fragment" }
        if (parsed.scheme != "https") {
            require(settings.allowInsecureLocalhost && parsed.host in LOCALHOSTS) {
                "apiUrl must use HTTPS"
            }
        }
        return if (parsed.encodedPath.trimEnd('/').endsWith(CHAT_COMPLETIONS_PATH)) {
            parsed
        } else {
            parsed.newBuilder().addPathSegments("chat/completions").build()
        }
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
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        const val USER_AGENT = "Masumi/0.1"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val LOCALHOSTS = setOf("localhost", "127.0.0.1", "::1")
        val SAFE_MODEL_ID = Regex("[A-Za-z0-9._:/-]+")

        fun waitWithCancellation(delayMillis: Long, isCancelled: () -> Boolean): Boolean {
            var remaining = delayMillis
            while (remaining > 0L) {
                if (isCancelled()) return false
                val sleepMillis = minOf(remaining, 100L)
                try {
                    Thread.sleep(sleepMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                remaining -= sleepMillis
            }
            return !isCancelled()
        }
    }
}
