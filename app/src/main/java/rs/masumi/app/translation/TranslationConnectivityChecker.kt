package rs.masumi.app.translation

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val DEFAULT_CONNECTIVITY_REQUEST_TIMEOUT_MILLIS = 12_000L

class TranslationConnectivityResult(
    val translation: String,
)

enum class TranslationConnectivityError {
    INVALID_CONFIGURATION,
    INVALID_ENDPOINT,
    AUTHENTICATION,
    ENDPOINT_NOT_FOUND,
    HTTP,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE,
    TIMEOUT,
    NETWORK,
    CANCELLED,
}

class TranslationConnectivityException(
    val code: TranslationConnectivityError,
    val httpStatus: Int? = null,
) : Exception(code.name) {
    override fun toString(): String =
        "TranslationConnectivityException(code=$code, httpStatus=$httpStatus)"
}

class TranslationConnectivityChecker(
    baseClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    requestTimeoutMillis: Long = DEFAULT_CONNECTIVITY_REQUEST_TIMEOUT_MILLIS,
) {
    private val client = baseClient.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    init {
        require(requestTimeoutMillis > 0L) { "requestTimeoutMillis must be positive" }
    }

    fun newCall(
        apiUrl: String,
        apiKey: String,
        model: String,
        allowInsecureLocalhost: Boolean = false,
    ): TranslationConnectivityCall {
        if (
            apiKey.isBlank() ||
            apiKey.length > MAXIMUM_API_KEY_LENGTH ||
            apiKey.any(Char::isISOControl) ||
            model.isBlank() ||
            model.length > MAXIMUM_MODEL_ID_LENGTH ||
            model.any(Char::isISOControl)
        ) {
            throw TranslationConnectivityException(
                TranslationConnectivityError.INVALID_CONFIGURATION,
            )
        }
        val completionUrl = try {
            OpenAiCompatibleEndpointResolver.completionUrl(
                apiUrl,
                allowInsecureLocalhost,
            ).toString()
        } catch (_: IllegalArgumentException) {
            throw TranslationConnectivityException(TranslationConnectivityError.INVALID_ENDPOINT)
        }
        return TranslationConnectivityCall(
            client = client,
            completionUrl = completionUrl,
            apiKey = apiKey,
            model = model,
            json = json,
        )
    }

    private companion object {
        const val MAXIMUM_API_KEY_LENGTH = 4_096
        const val MAXIMUM_MODEL_ID_LENGTH = 256
    }
}

class TranslationConnectivityCall internal constructor(
    private val client: OkHttpClient,
    private val completionUrl: String,
    private val apiKey: String,
    private val model: String,
    private val json: Json,
) {
    private val cancelled = AtomicBoolean(false)
    private val executed = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>()

    fun cancel() {
        cancelled.set(true)
        activeCall.get()?.cancel()
    }

    fun execute(): TranslationConnectivityResult {
        check(executed.compareAndSet(false, true)) { "connectivity calls are one-shot" }
        if (cancelled.get()) throw cancelled()
        val payload = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("max_tokens", MAXIMUM_OUTPUT_TOKENS)
            put("temperature", 0.0)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", SAMPLE_JAPANESE)
                    })
                },
            )
        }
        val request = Request.Builder()
            .url(completionUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        activeCall.set(call)
        try {
            if (cancelled.get()) {
                call.cancel()
                throw cancelled()
            }
            call.execute().use { response ->
                if (cancelled.get()) throw cancelled()
                if (!response.isSuccessful) {
                    throw TranslationConnectivityException(
                        code = when (response.code) {
                            401, 403 -> TranslationConnectivityError.AUTHENTICATION
                            404, 405 -> TranslationConnectivityError.ENDPOINT_NOT_FOUND
                            else -> TranslationConnectivityError.HTTP
                        },
                        httpStatus = response.code,
                    )
                }
                val body = readBoundedBody(response.body?.source())
                return parseResponse(body)
            }
        } catch (failure: TranslationConnectivityException) {
            throw failure
        } catch (_: InterruptedIOException) {
            if (cancelled.get()) throw cancelled()
            throw TranslationConnectivityException(TranslationConnectivityError.TIMEOUT)
        } catch (_: IOException) {
            if (cancelled.get()) throw cancelled()
            throw TranslationConnectivityException(TranslationConnectivityError.NETWORK)
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun parseResponse(body: String): TranslationConnectivityResult {
        try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: throw malformed()
            val choices = root["choices"] as? JsonArray ?: throw malformed()
            val choice = choices.firstOrNull() as? JsonObject ?: throw malformed()
            val message = choice["message"] as? JsonObject ?: throw malformed()
            val content = message["content"] as? JsonPrimitive ?: throw malformed()
            val translation = content.contentOrNull
                ?.takeIf { content.isString && it.isNotBlank() }
                ?: throw malformed()
            return TranslationConnectivityResult(translation)
        } catch (failure: TranslationConnectivityException) {
            throw failure
        } catch (_: SerializationException) {
            throw malformed()
        } catch (_: IllegalArgumentException) {
            throw malformed()
        }
    }

    private fun readBoundedBody(source: okio.BufferedSource?): String {
        source ?: throw malformed()
        source.request(MAXIMUM_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAXIMUM_RESPONSE_BYTES) {
            throw TranslationConnectivityException(
                TranslationConnectivityError.RESPONSE_TOO_LARGE,
            )
        }
        return source.buffer.clone().readUtf8()
    }

    private fun malformed() =
        TranslationConnectivityException(TranslationConnectivityError.MALFORMED_RESPONSE)

    private fun cancelled() =
        TranslationConnectivityException(TranslationConnectivityError.CANCELLED)

    private companion object {
        const val MAXIMUM_OUTPUT_TOKENS = 128
        const val MAXIMUM_RESPONSE_BYTES = 256L * 1_024L
        const val SYSTEM_PROMPT =
            "你是日译中翻译器。请将用户提供的日文翻译成简体中文，只返回中文译文，不要解释。"
        const val SAMPLE_JAPANESE =
            "おはよう。今日の天気はどう？一緒に天守閣公園を散歩しない？"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
