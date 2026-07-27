package rs.masumi.app.translation

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class AvailableTranslationModel(
    val id: String,
    val ownedBy: String?,
)

enum class TranslationModelCatalogError {
    INVALID_ENDPOINT,
    AUTHENTICATION,
    ENDPOINT_NOT_FOUND,
    TIMEOUT,
    NETWORK,
    HTTP,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE,
    CANCELLED,
}

class TranslationModelCatalogException(
    val code: TranslationModelCatalogError,
    val httpStatus: Int? = null,
) : Exception(code.name)

class TranslationModelCatalogClient(
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun newCall(
        apiUrl: String,
        apiKey: String,
        allowInsecureLocalhost: Boolean = false,
    ): TranslationModelCatalogCall {
        require(apiKey.isNotBlank() && apiKey.length <= MAXIMUM_API_KEY_LENGTH) {
            "apiKey must be a bounded non-empty value"
        }
        val urls = runCatching {
            OpenAiCompatibleEndpointResolver.modelUrls(apiUrl, allowInsecureLocalhost)
        }.getOrElse {
            throw TranslationModelCatalogException(TranslationModelCatalogError.INVALID_ENDPOINT)
        }
        return TranslationModelCatalogCall(
            client = baseClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build(),
            urls = urls,
            apiKey = apiKey,
            json = json,
        )
    }

    companion object {
        private const val REQUEST_TIMEOUT_SECONDS = 15L
        private const val MAXIMUM_API_KEY_LENGTH = 4_096
    }
}

class TranslationModelCatalogCall internal constructor(
    private val client: OkHttpClient,
    private val urls: List<HttpUrl>,
    private val apiKey: String,
    private val json: Json,
) {
    private val cancelled = AtomicBoolean(false)
    private val executed = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>()

    fun cancel() {
        cancelled.set(true)
        activeCall.get()?.cancel()
    }

    fun execute(): List<AvailableTranslationModel> {
        check(executed.compareAndSet(false, true)) { "model catalog calls are one-shot" }
        var endpointWasMissing = false
        urls.forEach { url ->
            if (cancelled.get()) throw cancelled()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val call = client.newCall(request)
            activeCall.set(call)
            try {
                call.execute().use { response ->
                    if (cancelled.get()) throw cancelled()
                    if (response.code == 404 || response.code == 405) {
                        endpointWasMissing = true
                        return@forEach
                    }
                    if (response.code == 401 || response.code == 403) {
                        throw TranslationModelCatalogException(
                            TranslationModelCatalogError.AUTHENTICATION,
                            response.code,
                        )
                    }
                    if (!response.isSuccessful) {
                        throw TranslationModelCatalogException(
                            TranslationModelCatalogError.HTTP,
                            response.code,
                        )
                    }
                    return parseModels(readBoundedBody(response.body?.source()))
                }
            } catch (failure: TranslationModelCatalogException) {
                throw failure
            } catch (_: SocketTimeoutException) {
                throw TranslationModelCatalogException(TranslationModelCatalogError.TIMEOUT)
            } catch (_: IOException) {
                if (cancelled.get()) throw cancelled()
                throw TranslationModelCatalogException(TranslationModelCatalogError.NETWORK)
            } finally {
                activeCall.compareAndSet(call, null)
            }
        }
        throw TranslationModelCatalogException(
            if (endpointWasMissing) {
                TranslationModelCatalogError.ENDPOINT_NOT_FOUND
            } else {
                TranslationModelCatalogError.INVALID_ENDPOINT
            },
        )
    }

    private fun readBoundedBody(source: okio.BufferedSource?): String {
        source ?: throw TranslationModelCatalogException(
            TranslationModelCatalogError.MALFORMED_RESPONSE,
        )
        source.request(MAXIMUM_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAXIMUM_RESPONSE_BYTES) {
            throw TranslationModelCatalogException(TranslationModelCatalogError.RESPONSE_TOO_LARGE)
        }
        return source.buffer.clone().readUtf8()
    }

    private fun parseModels(body: String): List<AvailableTranslationModel> {
        try {
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                ?: throw TranslationModelCatalogException(
                    TranslationModelCatalogError.MALFORMED_RESPONSE,
                )
            return data.mapNotNull { element ->
                val entry = element.jsonObject
                val id = entry["id"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf(::isSafeModelId)
                    ?: return@mapNotNull null
                AvailableTranslationModel(
                    id = id,
                    ownedBy = entry["owned_by"]?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && it.length <= MAXIMUM_OWNER_LENGTH },
                )
            }.distinctBy(AvailableTranslationModel::id)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, AvailableTranslationModel::id))
        } catch (failure: TranslationModelCatalogException) {
            throw failure
        } catch (_: SerializationException) {
            throw TranslationModelCatalogException(TranslationModelCatalogError.MALFORMED_RESPONSE)
        } catch (_: IllegalArgumentException) {
            throw TranslationModelCatalogException(TranslationModelCatalogError.MALFORMED_RESPONSE)
        }
    }

    private fun isSafeModelId(id: String): Boolean =
        id.isNotEmpty() &&
            id.length <= MAXIMUM_MODEL_ID_LENGTH &&
            id.none(Char::isISOControl)

    private fun cancelled() =
        TranslationModelCatalogException(TranslationModelCatalogError.CANCELLED)

    private companion object {
        const val USER_AGENT = "Masumi/0.1"
        const val MAXIMUM_RESPONSE_BYTES = 2L * 1_024L * 1_024L
        const val MAXIMUM_MODEL_ID_LENGTH = 256
        const val MAXIMUM_OWNER_LENGTH = 256
    }
}
