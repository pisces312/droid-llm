#include <android/log.h>
#include <jni.h>

#include <string>
#include <vector>

#include "mnn_chat_core.hpp"

#define TAG "mnn_chat_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define JNI_METHOD(name) \
  Java_io_github_pisces312_droidllm_engine_mnn_MnnNative_##name

namespace {

using droidllm::mnnchat::ChatSession;
using droidllm::mnnchat::GenerateMetrics;

void throw_java(JNIEnv* env, const char* class_name, const char* message) {
  jclass clazz = env->FindClass(class_name);
  if (clazz != nullptr) {
    env->ThrowNew(clazz, message);
    env->DeleteLocalRef(clazz);
  }
}

std::string jstring_to_std(JNIEnv* env, jstring value) {
  if (value == nullptr) return "";
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string out = chars != nullptr ? chars : "";
  if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
  return out;
}

ChatSession* as_session(jlong handle) {
  return reinterpret_cast<ChatSession*>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL JNI_METHOD(nativeCreate)(JNIEnv* env, jclass,
                                                 jstring jmodel_dir,
                                                 jstring jconfig_json) {
  const std::string config_path = jstring_to_std(env, jmodel_dir);
  if (config_path.empty()) {
    throw_java(env, "java/lang/IllegalArgumentException", "model dir empty");
    return 0;
  }
  LOGI("createLLM %s", config_path.c_str());
  const std::string cfg = jstring_to_std(env, jconfig_json);
  auto session = ChatSession::Create(config_path, cfg);
  if (session == nullptr) {
    LOGE("createLLM/load failed");
    return 0;
  }
  return reinterpret_cast<jlong>(session.release());
}

JNIEXPORT void JNICALL JNI_METHOD(nativeDestroy)(JNIEnv* /*env*/, jclass,
                                                 jlong handle) {
  auto* session = as_session(handle);
  if (session == nullptr) return;
  if (!session->Destroy()) {
    LOGE("nativeDestroy while generating");
    return;
  }
  delete session;
}

JNIEXPORT void JNICALL JNI_METHOD(nativeSetConfig)(JNIEnv* env, jclass,
                                                   jlong handle,
                                                   jstring jconfig_json) {
  auto* session = as_session(handle);
  if (session == nullptr) return;
  session->SetConfig(jstring_to_std(env, jconfig_json));
}

JNIEXPORT void JNICALL JNI_METHOD(nativeReset)(JNIEnv* /*env*/, jclass,
                                               jlong handle) {
  auto* session = as_session(handle);
  if (session == nullptr) return;
  session->Reset();
}

/**
 * Ask an in-flight nativeGenerate to stop at the next token boundary.
 * Safe to call from any thread while generate is blocked.
 */
JNIEXPORT void JNICALL JNI_METHOD(nativeRequestCancel)(JNIEnv* /*env*/, jclass,
                                                       jlong handle) {
  auto* session = as_session(handle);
  if (session == nullptr) return;
  session->RequestCancel();
}

/**
 * messages: String[] alternating role, content: [role0, content0, role1, content1, ...]
 * callback: MnnNative.TokenCallback with onToken(String)
 *
 * Blocks until generation finishes. Returns generated token count via out
 * metrics array: [promptTokens, generatedTokens, prefillUs, decodeUs, ttftUs]
 */
JNIEXPORT void JNICALL JNI_METHOD(nativeGenerate)(
    JNIEnv* env, jclass, jlong handle, jobjectArray jmessages,
    jint max_new_tokens, jobject jcallback, jlongArray jmetrics_out) {
  auto* session = as_session(handle);
  if (session == nullptr) {
    throw_java(env, "java/lang/IllegalStateException", "session not loaded");
    return;
  }

  jclass cb_class = env->GetObjectClass(jcallback);
  jmethodID on_token =
      env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");

  std::vector<std::string> flat;
  if (jmessages != nullptr) {
    const jsize n = env->GetArrayLength(jmessages);
    flat.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
      jstring jpart = static_cast<jstring>(env->GetObjectArrayElement(jmessages, i));
      flat.push_back(jstring_to_std(env, jpart));
      env->DeleteLocalRef(jpart);
    }
  }

  GenerateMetrics metrics;
  std::string error;
  const bool ok = session->Generate(
      flat, max_new_tokens,
      [&](const std::string& piece) {
        jstring jpiece = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(jcallback, on_token, jpiece);
        env->DeleteLocalRef(jpiece);
      },
      &metrics, &error);
  if (!ok) {
    throw_java(env, "java/lang/IllegalStateException", error.c_str());
    return;
  }

  if (jmetrics_out != nullptr) {
    jlong out[5] = {
        metrics.prompt_tokens, metrics.generated_tokens, metrics.prefill_us,
        metrics.decode_us, metrics.ttfa_us,
    };
    env->SetLongArrayRegion(jmetrics_out, 0, 5, out);
  }
}

JNIEXPORT jstring JNICALL JNI_METHOD(nativeVersion)(JNIEnv* env, jclass) {
  return env->NewStringUTF(droidllm::mnnchat::MnnVersion());
}

}  // extern "C"
