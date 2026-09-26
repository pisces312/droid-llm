// Thin JNI wrapper around Qualcomm Genie Dialog API.
// Package: io.github.pisces312.droidllm.engine.genie
// Linked against libGenie.so from the local QAIRT SDK (QAIRT_PATH).

#include <jni.h>

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include <android/log.h>

#include "GenieCommon.h"
#include "GenieDialog.h"

#define LOG_TAG "GenieChatJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct TokenCallbackRef {
    JNIEnv* env = nullptr;
    jobject callback = nullptr;  // local ref held for the duration of nativeGenerate
    jmethodID onTokenMethod = nullptr;
    std::atomic<int> tokenCount{0};
    std::string firstPiece;
    bool sawFirst = false;
};

struct GenieSession {
    GenieDialogConfig_Handle_t config = nullptr;
    GenieDialog_Handle_t dialog = nullptr;
    std::string modelDir;
};

void JNICALL QueryCallback(const char* response,
                           const GenieDialog_SentenceCode_t sentenceCode,
                           const void* userData) {
    if (response == nullptr || userData == nullptr) return;
    if (sentenceCode == GENIE_DIALOG_SENTENCE_ABORT) return;

    auto* cb = static_cast<TokenCallbackRef*>(const_cast<void*>(userData));
    if (*response == '\0') return;

    cb->tokenCount.fetch_add(1);
    if (!cb->sawFirst) {
        cb->sawFirst = true;
        cb->firstPiece = response;
    }
    if (cb->env != nullptr && cb->callback != nullptr && cb->onTokenMethod != nullptr) {
        jstring piece = cb->env->NewStringUTF(response);
        if (piece != nullptr) {
            cb->env->CallVoidMethod(cb->callback, cb->onTokenMethod, piece);
            cb->env->DeleteLocalRef(piece);
        }
    }
}

std::string JStringToStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

void ThrowRuntime(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, msg);
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_pisces312_droidllm_engine_genie_GenieNative_nativeCreate(
    JNIEnv* env, jobject /*this*/, jstring configJson) {
    try {
        const std::string config = JStringToStd(env, configJson);
        if (config.empty()) {
            ThrowRuntime(env, "Genie config JSON is empty");
            return 0;
        }

        auto* session = new GenieSession();
        if (GENIE_STATUS_SUCCESS !=
            GenieDialogConfig_createFromJson(config.c_str(), &session->config)) {
            delete session;
            ThrowRuntime(env, "GenieDialogConfig_createFromJson failed");
            return 0;
        }
        if (GENIE_STATUS_SUCCESS != GenieDialog_create(session->config, &session->dialog)) {
            if (session->config != nullptr) GenieDialogConfig_free(session->config);
            delete session;
            ThrowRuntime(env, "GenieDialog_create failed");
            return 0;
        }
        LOGI("Genie dialog created");
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception& e) {
        ThrowRuntime(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_pisces312_droidllm_engine_genie_GenieNative_nativeDestroy(
    JNIEnv* env, jobject /*this*/, jlong handle) {
    if (handle == 0) return;
    auto* session = reinterpret_cast<GenieSession*>(handle);
    if (session->dialog != nullptr) {
        GenieDialog_free(session->dialog);
        session->dialog = nullptr;
    }
    if (session->config != nullptr) {
        GenieDialogConfig_free(session->config);
        session->config = nullptr;
    }
    delete session;
    LOGI("Genie dialog destroyed");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_pisces312_droidllm_engine_genie_GenieNative_nativeReset(
    JNIEnv* env, jobject /*this*/, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    auto* session = reinterpret_cast<GenieSession*>(handle);
    if (session->dialog == nullptr) return JNI_FALSE;
    return GENIE_STATUS_SUCCESS == GenieDialog_reset(session->dialog) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_pisces312_droidllm_engine_genie_GenieNative_nativeSetMaxNumTokens(
    JNIEnv* env, jobject /*this*/, jlong handle, jint maxNumTokens) {
    if (handle == 0 || maxNumTokens <= 0) return;
    auto* session = reinterpret_cast<GenieSession*>(handle);
    if (session->dialog == nullptr) return;
    GenieDialog_setMaxNumTokens(session->dialog, static_cast<uint32_t>(maxNumTokens));
}

/**
 * Blocking generate. Streams pieces via callback.onToken(String).
 * metricsOut size >= 2: [0]=generatedPieceCount, [1]=status (0 ok, 1 empty, 2 query_failed).
 * Returns true when at least one piece was produced.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_pisces312_droidllm_engine_genie_GenieNative_nativeGenerate(
    JNIEnv* env, jobject /*this*/, jlong handle, jstring prompt, jobject callback,
    jlongArray metricsOut) {
    if (handle == 0) {
        ThrowRuntime(env, "Genie session handle is null");
        return JNI_FALSE;
    }
    auto* session = reinterpret_cast<GenieSession*>(handle);
    if (session->dialog == nullptr) {
        ThrowRuntime(env, "Genie dialog is null");
        return JNI_FALSE;
    }

    const std::string query = JStringToStd(env, prompt);
    if (query.empty()) {
        ThrowRuntime(env, "Genie query prompt is empty");
        return JNI_FALSE;
    }

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    if (onTokenMethod == nullptr) {
        ThrowRuntime(env, "TokenCallback.onToken(String) not found");
        return JNI_FALSE;
    }

    TokenCallbackRef cb;
    cb.env = env;
    cb.callback = callback;
    cb.onTokenMethod = onTokenMethod;

    const Genie_Status_t status = GenieDialog_query(
        session->dialog, query.c_str(), GENIE_DIALOG_SENTENCE_COMPLETE, QueryCallback, &cb);

    const jlong generated = cb.tokenCount.load();
    const bool ok = status == GENIE_STATUS_SUCCESS && generated > 0;
    const jlong statusOut = (status != GENIE_STATUS_SUCCESS) ? 2L : (generated > 0 ? 0L : 1L);

    if (metricsOut != nullptr) {
        const jsize n = env->GetArrayLength(metricsOut);
        if (n >= 2) {
            jlong buf[2] = {generated, statusOut};
            env->SetLongArrayRegion(metricsOut, 0, 2, buf);
        }
    }

    if (status != GENIE_STATUS_SUCCESS) {
        LOGE("GenieDialog_query failed status=%d pieces=%d", (int)status, (int)generated);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_pisces312_droidllm_engine_genie_GenieNative_nativeVersion(
    JNIEnv* env, jobject /*this*/) {
    return env->NewStringUTF("genie_chat_jni/1.0 qairt");
}
