#include <jni.h>

#include "llama.h"
#include "ggml-backend.h"
#include "mtmd-helper.h"
#include "mtmd.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <iomanip>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>

namespace {

constexpr uint32_t kContextSize = 1024;
constexpr uint32_t kBatchSize = 128;
constexpr int kMaximumGeneratedTokens = 256;
constexpr int kImageMinTokens = 64;
// Large vertical manga captions can span most of a page. Keeping their visual
// budget below the desktop-oriented default avoids exhausting mobile Vulkan
// drivers while retaining ample resolution for the oversized glyphs.
constexpr int kImageMaxTokens = 128;
constexpr int64_t kVulkanInferenceTimeoutMillis = 75'000;
constexpr int64_t kCpuInferenceTimeoutMillis = 30'000;

void silent_log(enum ggml_log_level, const char *, void *) {}

enum class ExecutionBackend {
    Vulkan,
    Cpu,
};

struct EngineHandle {
    llama_model * model = nullptr;
    mtmd_context * vision = nullptr;
    llama_context * context = nullptr;
    ExecutionBackend backend = ExecutionBackend::Cpu;
    std::atomic<bool> cancelled{false};
    std::atomic<int64_t> deadline_nanos{0};
    std::mutex inference_mutex;

    ~EngineHandle() {
        if (context != nullptr) llama_free(context);
        if (vision != nullptr) mtmd_free(vision);
        if (model != nullptr) llama_model_free(model);
    }
};

struct BitmapDeleter {
    void operator()(mtmd_bitmap * value) const {
        if (value != nullptr) mtmd_bitmap_free(value);
    }
};

struct ChunksDeleter {
    void operator()(mtmd_input_chunks * value) const {
        if (value != nullptr) mtmd_input_chunks_free(value);
    }
};

using BitmapPtr = std::unique_ptr<mtmd_bitmap, BitmapDeleter>;
using ChunksPtr = std::unique_ptr<mtmd_input_chunks, ChunksDeleter>;

struct InferenceResult {
    std::string text;
    std::vector<llama_token> token_ids;
    std::vector<double> token_probabilities;
    int visual_token_count = 0;
    bool reached_eos = false;
    bool truncated = false;
    bool repetition_stopped = false;
    int64_t prompt_evaluation_millis = 0;
    int64_t generation_millis = 0;
};

EngineHandle * from_handle(jlong value) {
    return reinterpret_cast<EngineHandle *>(value);
}

bool has_accelerator_device() {
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        const auto type = ggml_backend_dev_type(ggml_backend_dev_get(index));
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU ||
            type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            return true;
        }
    }
    return false;
}

int64_t steady_nanos() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

bool deadline_exceeded(const EngineHandle * handle) {
    const int64_t deadline = handle->deadline_nanos.load();
    return deadline > 0 && steady_nanos() >= deadline;
}

const char * abort_error(const EngineHandle * handle) {
    if (handle->cancelled.load()) return "CANCELLED";
    if (deadline_exceeded(handle)) return "TIMEOUT";
    return nullptr;
}

bool abort_requested(void * user_data) {
    return abort_error(static_cast<EngineHandle *>(user_data)) != nullptr;
}

jstring error_json(JNIEnv * env, const char * code) {
    const std::string json = std::string("{\"errorCode\":\"") + code + "\"}";
    return env->NewStringUTF(json.c_str());
}

std::string escape_json(const std::string & value) {
    std::ostringstream escaped;
    for (const unsigned char byte : value) {
        switch (byte) {
            case '\"': escaped << "\\\""; break;
            case '\\': escaped << "\\\\"; break;
            case '\b': escaped << "\\b"; break;
            case '\f': escaped << "\\f"; break;
            case '\n': escaped << "\\n"; break;
            case '\r': escaped << "\\r"; break;
            case '\t': escaped << "\\t"; break;
            default:
                if (byte < 0x20) {
                    escaped << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                            << static_cast<int>(byte) << std::dec;
                } else {
                    escaped << static_cast<char>(byte);
                }
        }
    }
    return escaped.str();
}

bool valid_utf8(const std::string & value) {
    size_t index = 0;
    while (index < value.size()) {
        const uint8_t first = static_cast<uint8_t>(value[index]);
        size_t continuation_count = 0;
        uint32_t code_point = 0;
        if (first <= 0x7f) {
            index += 1;
            continue;
        } else if ((first & 0xe0) == 0xc0) {
            continuation_count = 1;
            code_point = first & 0x1f;
            if (code_point == 0) return false;
        } else if ((first & 0xf0) == 0xe0) {
            continuation_count = 2;
            code_point = first & 0x0f;
        } else if ((first & 0xf8) == 0xf0) {
            continuation_count = 3;
            code_point = first & 0x07;
        } else {
            return false;
        }
        if (index + continuation_count >= value.size()) return false;
        for (size_t offset = 1; offset <= continuation_count; ++offset) {
            const uint8_t next = static_cast<uint8_t>(value[index + offset]);
            if ((next & 0xc0) != 0x80) return false;
            code_point = (code_point << 6) | (next & 0x3f);
        }
        if ((continuation_count == 1 && code_point < 0x80) ||
            (continuation_count == 2 && code_point < 0x800) ||
            (continuation_count == 3 && code_point < 0x10000) ||
            code_point > 0x10ffff ||
            (code_point >= 0xd800 && code_point <= 0xdfff)) {
            return false;
        }
        index += continuation_count + 1;
    }
    return true;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int32_t length = llama_token_to_piece(
        vocab,
        token,
        buffer.data(),
        static_cast<int32_t>(buffer.size()),
        0,
        true);
    if (length < 0) {
        buffer.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(
            vocab,
            token,
            buffer.data(),
            static_cast<int32_t>(buffer.size()),
            0,
            true);
    }
    if (length < 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(length));
}

bool has_pathological_repetition(const std::vector<llama_token> & tokens) {
    constexpr size_t kMinimumRepeatedTokens = 8;
    constexpr size_t kMinimumRepetitions = 4;
    if (tokens.size() < kMinimumRepeatedTokens) return false;
    const size_t maximum_unit = std::min<size_t>(8, tokens.size() / kMinimumRepetitions);
    for (size_t unit = 1; unit <= maximum_unit; ++unit) {
        const size_t repeated_length = unit * kMinimumRepetitions;
        bool matches = true;
        for (size_t offset = 0; offset < repeated_length; ++offset) {
            const size_t index = tokens.size() - repeated_length + offset;
            const size_t reference = tokens.size() - repeated_length + (offset % unit);
            if (tokens[index] != tokens[reference]) {
                matches = false;
                break;
            }
        }
        if (matches) return true;
    }
    return false;
}

std::string format_paddle_ocr_prompt(const std::string & content) {
    // PaddleOCR-VL's embedded template is Jinja-only in the pinned model and is
    // not supported by llama_chat_apply_template's legacy template detector.
    // This is the exact single-turn rendering of that public model template.
    return "<|begin_of_sentence|>User: " + content + "\nAssistant:\n";
}

bool select_greedy_token(
    llama_context * context,
    const llama_vocab * vocab,
    const std::vector<llama_token> & generated,
    double repetition_penalty,
    llama_token * selected_token,
    double * selected_probability) {
    float * logits = llama_get_logits_ith(context, -1);
    if (logits == nullptr) return false;
    const int32_t vocabulary_size = llama_vocab_n_tokens(vocab);
    if (vocabulary_size <= 0) return false;
    const std::unordered_set<llama_token> repeated_tokens(generated.begin(), generated.end());
    auto adjusted_logit = [&](int32_t token) {
        double value = logits[token];
        if (repeated_tokens.find(token) != repeated_tokens.end()) {
            value = value <= 0.0 ? value * repetition_penalty : value / repetition_penalty;
        }
        return value;
    };

    int32_t best_token = 0;
    double best_logit = -std::numeric_limits<double>::infinity();
    for (int32_t token = 0; token < vocabulary_size; ++token) {
        const double value = adjusted_logit(token);
        if (value > best_logit) {
            best_logit = value;
            best_token = token;
        }
    }
    if (!std::isfinite(best_logit)) return false;
    double probability_sum = 0.0;
    for (int32_t token = 0; token < vocabulary_size; ++token) {
        probability_sum += std::exp(adjusted_logit(token) - best_logit);
    }
    if (!std::isfinite(probability_sum) || probability_sum <= 0.0) return false;
    *selected_token = best_token;
    *selected_probability = 1.0 / probability_sum;
    return true;
}

const char * run_inference(
    EngineHandle * handle,
    const uint8_t * rgb,
    int width,
    int height,
    const std::string & prompt,
    int maximum_generated_tokens,
    double repetition_penalty,
    InferenceResult * result) {
    handle->cancelled.store(false);
    const int64_t timeout_millis = handle->backend == ExecutionBackend::Vulkan
        ? kVulkanInferenceTimeoutMillis
        : kCpuInferenceTimeoutMillis;
    handle->deadline_nanos.store(steady_nanos() + timeout_millis * 1'000'000);
    struct DeadlineReset {
        EngineHandle * handle;
        ~DeadlineReset() { handle->deadline_nanos.store(0); }
    } deadline_reset{handle};
    llama_memory_clear(llama_get_memory(handle->context), false);
    llama_set_causal_attn(handle->context, true);

    BitmapPtr bitmap(mtmd_bitmap_init(
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height),
        rgb));
    if (!bitmap) return "IMAGE_INVALID";
    const std::string content = std::string(mtmd_default_marker()) + prompt;
    const std::string formatted_prompt = format_paddle_ocr_prompt(content);
    if (formatted_prompt.empty()) return "TOKENIZE";
    mtmd_input_text input_text{
        formatted_prompt.c_str(),
        true,
        true,
    };
    ChunksPtr chunks(mtmd_input_chunks_init());
    if (!chunks) return "CONTEXT";
    const mtmd_bitmap * bitmap_pointer = bitmap.get();
    if (const char * abort = abort_error(handle)) return abort;
    if (mtmd_tokenize(
            handle->vision,
            chunks.get(),
            &input_text,
            &bitmap_pointer,
            1) != 0) {
        return "TOKENIZE";
    }
    for (size_t index = 0; index < mtmd_input_chunks_size(chunks.get()); ++index) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks.get(), index);
        if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_IMAGE) {
            result->visual_token_count += static_cast<int>(mtmd_input_chunk_get_n_tokens(chunk));
        }
    }
    if (const char * abort = abort_error(handle)) return abort;
    llama_pos n_past = 0;
    const auto prompt_start = std::chrono::steady_clock::now();
    const int32_t eval_result = mtmd_helper_eval_chunks(
        handle->vision,
        handle->context,
        chunks.get(),
        0,
        0,
        static_cast<int32_t>(kBatchSize),
        true,
        &n_past);
    result->prompt_evaluation_millis = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - prompt_start).count();
    if (eval_result != 0) {
        if (const char * abort = abort_error(handle)) return abort;
        return "DECODE";
    }

    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    llama_batch batch = llama_batch_init(1, 0, 1);
    const auto generation_start = std::chrono::steady_clock::now();
    const int generation_limit = std::min(maximum_generated_tokens, kMaximumGeneratedTokens);
    const char * error = nullptr;
    for (int generated_count = 0; generated_count < generation_limit; ++generated_count) {
        if (const char * abort = abort_error(handle)) {
            error = abort;
            break;
        }
        llama_token token = 0;
        double probability = 0.0;
        if (!select_greedy_token(
                handle->context,
                vocab,
                result->token_ids,
                repetition_penalty,
                &token,
                &probability)) {
            error = "DECODE";
            break;
        }
        if (llama_vocab_is_eog(vocab, token)) {
            result->reached_eos = true;
            break;
        }
        result->token_ids.push_back(token);
        result->token_probabilities.push_back(probability);
        result->text += token_piece(vocab, token);
        if (has_pathological_repetition(result->token_ids)) {
            result->repetition_stopped = true;
            break;
        }
        if (generated_count == generation_limit - 1) {
            result->truncated = true;
            break;
        }
        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = n_past++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        const int32_t decode_result = llama_decode(handle->context, batch);
        if (decode_result != 0) {
            error = abort_error(handle);
            if (error == nullptr) error = "DECODE";
            break;
        }
    }
    result->generation_millis = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - generation_start).count();
    llama_batch_free(batch);
    if (error != nullptr) return error;
    if (!valid_utf8(result->text)) return "UTF8";
    return nullptr;
}

std::string result_json(const InferenceResult & result, int width, int height) {
    std::ostringstream json;
    json << "{\"rawText\":\"" << escape_json(result.text) << "\",\"tokenIds\":[";
    for (size_t index = 0; index < result.token_ids.size(); ++index) {
        if (index > 0) json << ',';
        json << result.token_ids[index];
    }
    json << "],\"tokenProbabilities\":[" << std::setprecision(12);
    for (size_t index = 0; index < result.token_probabilities.size(); ++index) {
        if (index > 0) json << ',';
        json << result.token_probabilities[index];
    }
    json << "],\"sourceWidth\":" << width
         << ",\"sourceHeight\":" << height
         << ",\"processedWidth\":" << width
         << ",\"processedHeight\":" << height
         << ",\"visualTokenCount\":" << result.visual_token_count
         << ",\"generatedTokenCount\":" << result.token_ids.size()
         << ",\"reachedEos\":" << (result.reached_eos ? "true" : "false")
         << ",\"truncated\":" << (result.truncated ? "true" : "false")
         << ",\"repetitionStopped\":" << (result.repetition_stopped ? "true" : "false")
         << ",\"promptEvaluationMillis\":" << result.prompt_evaluation_millis
         << ",\"generationMillis\":" << result.generation_millis
         << '}';
    return json.str();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_rs_masumi_app_ocr_JniNativeOcrBridge_nativeCreate(
    JNIEnv * env,
    jobject,
    jstring model_path,
    jstring projector_path,
    jboolean prefer_gpu_value,
    jint thread_count_value) {
    if (model_path == nullptr || projector_path == nullptr) return -1;
    const int thread_count = std::min(std::max(static_cast<int>(thread_count_value), 1), 8);
    const char * model = env->GetStringUTFChars(model_path, nullptr);
    const char * projector = env->GetStringUTFChars(projector_path, nullptr);
    if (model == nullptr || projector == nullptr) {
        if (model != nullptr) env->ReleaseStringUTFChars(model_path, model);
        if (projector != nullptr) env->ReleaseStringUTFChars(projector_path, projector);
        return -1;
    }
    const std::string model_file(model);
    const std::string projector_file(projector);
    env->ReleaseStringUTFChars(model_path, model);
    env->ReleaseStringUTFChars(projector_path, projector);
    const bool prefer_gpu = prefer_gpu_value == JNI_TRUE;
    try {
        static std::once_flag backend_once;
        std::call_once(backend_once, []() {
            // Some Android Adreno drivers advertise BF16 shader support but crash
            // inside the vendor compiler when ggml creates its BF16 mat-vec pipeline.
            // Prefer the portable Vulkan kernels over a process-level driver crash.
            // F16 kernels stay enabled: Adreno 7xx executes them correctly and at
            // roughly twice the FP32 mat-vec throughput; a driver that cannot
            // compile them fails engine creation, which falls back to CPU through
            // the existing ACCELERATOR_UNAVAILABLE path instead of crashing.
            setenv("GGML_VK_DISABLE_BFLOAT16", "1", 0);
            setenv("GGML_VK_DISABLE_ASYNC", "1", 0);
            llama_log_set(silent_log, nullptr);
            mtmd_log_set(silent_log, nullptr);
            llama_backend_init();
        });
        if (prefer_gpu && !has_accelerator_device()) return -5;
        auto handle = std::make_unique<EngineHandle>();
        handle->backend = prefer_gpu ? ExecutionBackend::Vulkan : ExecutionBackend::Cpu;
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = prefer_gpu ? 1000 : 0;
        model_params.use_mmap = true;
        handle->model = llama_model_load_from_file(model_file.c_str(), model_params);
        if (handle->model == nullptr) {
            return -1;
        }
        if (llama_model_chat_template(handle->model, nullptr) == nullptr) {
            return -4;
        }
        mtmd_context_params vision_params = mtmd_context_params_default();
        // Measured on Adreno 750 (Xiaomi 14): the SigLIP-style vision encoder's
        // conv/attention graph runs ~5x slower through ggml-vulkan than through
        // the optimized CPU backend (42s vs 8.8s per crop), while the language
        // model layers do benefit from the GPU. Keep the projector on CPU and
        // offload only the LLM.
        vision_params.use_gpu = false;
        vision_params.print_timings = false;
        vision_params.n_threads = thread_count;
        vision_params.warmup = false;
        // Preserve crop-adaptive preprocessing while avoiding the projector's
        // full-page minimum (576 input patches) for small text boxes. The actual
        // token count still follows each crop's own width, height, and aspect ratio.
        vision_params.image_min_tokens = kImageMinTokens;
        vision_params.image_max_tokens = kImageMaxTokens;
        handle->vision = mtmd_init_from_file(projector_file.c_str(), handle->model, vision_params);
        if (handle->vision == nullptr) return -2;
        if (!mtmd_support_vision(handle->vision)) return -3;
        llama_context_params context_params = llama_context_default_params();
        context_params.n_ctx = kContextSize;
        context_params.n_batch = kBatchSize;
        context_params.n_ubatch = kBatchSize;
        context_params.n_seq_max = 1;
        context_params.n_threads = thread_count;
        context_params.n_threads_batch = thread_count;
        context_params.offload_kqv = prefer_gpu;
        context_params.abort_callback = abort_requested;
        context_params.abort_callback_data = handle.get();
        handle->context = llama_init_from_model(handle->model, context_params);
        if (handle->context == nullptr) return 0;
        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return prefer_gpu ? -5 : -1;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_rs_masumi_app_ocr_JniNativeOcrBridge_executionBackend(
    JNIEnv * env,
    jobject,
    jlong handle_value) {
    const EngineHandle * handle = from_handle(handle_value);
    if (handle == nullptr) return env->NewStringUTF("");
    return env->NewStringUTF(
        handle->backend == ExecutionBackend::Vulkan ? "VULKAN" : "CPU");
}

extern "C" JNIEXPORT jstring JNICALL
Java_rs_masumi_app_ocr_JniNativeOcrBridge_recognize(
    JNIEnv * env,
    jobject,
    jlong handle_value,
    jbyteArray rgb,
    jint width,
    jint height,
    jstring prompt,
    jint maximum_generated_tokens,
    jdouble repetition_penalty) {
    EngineHandle * handle = from_handle(handle_value);
    if (handle == nullptr || rgb == nullptr || prompt == nullptr) return error_json(env, "CONTEXT");
    const int64_t expected_length = static_cast<int64_t>(width) * height * 3;
    if (width <= 0 || height <= 0 || expected_length <= 0 ||
        env->GetArrayLength(rgb) != expected_length ||
        maximum_generated_tokens <= 0 ||
        !std::isfinite(repetition_penalty) ||
        repetition_penalty <= 0.0) {
        return error_json(env, "IMAGE_INVALID");
    }
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    jbyte * rgb_bytes = env->GetByteArrayElements(rgb, nullptr);
    if (prompt_chars == nullptr || rgb_bytes == nullptr) {
        if (prompt_chars != nullptr) env->ReleaseStringUTFChars(prompt, prompt_chars);
        if (rgb_bytes != nullptr) env->ReleaseByteArrayElements(rgb, rgb_bytes, JNI_ABORT);
        return error_json(env, "IMAGE_INVALID");
    }
    std::lock_guard<std::mutex> lock(handle->inference_mutex);
    InferenceResult result;
    const char * error = nullptr;
    try {
        error = run_inference(
            handle,
            reinterpret_cast<uint8_t *>(rgb_bytes),
            width,
            height,
            prompt_chars,
            maximum_generated_tokens,
            repetition_penalty,
            &result);
    } catch (const std::exception & failure) {
        const std::string message = failure.what() == nullptr ? "" : failure.what();
        error = message.find("DeviceLost") != std::string::npos ||
            message.find("ErrorDeviceLost") != std::string::npos
            ? "ACCELERATOR_UNAVAILABLE"
            : "DECODE";
    } catch (...) {
        error = "DECODE";
    }
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    env->ReleaseByteArrayElements(rgb, rgb_bytes, JNI_ABORT);
    if (error != nullptr) return error_json(env, error);
    const std::string json = result_json(result, width, height);
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_rs_masumi_app_ocr_JniNativeOcrBridge_cancel(
    JNIEnv *,
    jobject,
    jlong handle_value) {
    EngineHandle * handle = from_handle(handle_value);
    if (handle != nullptr) handle->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_rs_masumi_app_ocr_JniNativeOcrBridge_destroy(
    JNIEnv *,
    jobject,
    jlong handle_value) {
    delete from_handle(handle_value);
}
