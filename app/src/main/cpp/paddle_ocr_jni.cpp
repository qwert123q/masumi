#include <jni.h>

#include <atomic>
#include <cstdio>
#include <new>
#include <string>

namespace {

struct EngineHandle {
    std::atomic<bool> cancelled{false};
};

bool readable_file(const char * path) {
    if (path == nullptr || path[0] == '\0') {
        return false;
    }
    FILE * file = std::fopen(path, "rb");
    if (file == nullptr) {
        return false;
    }
    std::fclose(file);
    return true;
}

jstring error_json(JNIEnv * env, const char * code) {
    const std::string json = std::string("{\"errorCode\":\"") + code + "\"}";
    return env->NewStringUTF(json.c_str());
}

EngineHandle * from_handle(jlong value) {
    return reinterpret_cast<EngineHandle *>(value);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_rs_masumi_app_ocr_JniNativeOcrBridge_create(
    JNIEnv * env,
    jobject,
    jstring model_path,
    jstring projector_path) {
    if (model_path == nullptr || projector_path == nullptr) {
        return -1;
    }
    const char * model = env->GetStringUTFChars(model_path, nullptr);
    const char * projector = env->GetStringUTFChars(projector_path, nullptr);
    const bool model_ok = readable_file(model);
    const bool projector_ok = readable_file(projector);
    env->ReleaseStringUTFChars(model_path, model);
    env->ReleaseStringUTFChars(projector_path, projector);
    if (!model_ok) return -1;
    if (!projector_ok) return -2;
    auto * handle = new (std::nothrow) EngineHandle();
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_rs_masumi_app_ocr_JniNativeOcrBridge_recognize(
    JNIEnv * env,
    jobject,
    jlong handle_value,
    jstring request_json,
    jbyteArray rgb) {
    EngineHandle * handle = from_handle(handle_value);
    if (handle == nullptr || request_json == nullptr || rgb == nullptr) {
        return error_json(env, "CONTEXT");
    }
    if (handle->cancelled.exchange(false)) {
        return error_json(env, "CANCELLED");
    }
    if (env->GetArrayLength(rgb) <= 0) {
        return error_json(env, "IMAGE_INVALID");
    }
    return error_json(env, "DECODE");
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
