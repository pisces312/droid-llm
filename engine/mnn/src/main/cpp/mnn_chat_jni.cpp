#include <android/log.h>
#include <jni.h>

#include <functional>
#include <memory>
#include <mutex>
#include <ostream>
#include <sstream>
#include <string>
#include <vector>

#include "llm/llm.hpp"

#define TAG "mnn_chat_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define JNI_METHOD(name) \
  Java_io_github_pisces312_droidllm_engine_mnn_MnnNative_##name

using MNN::Transformer::Llm;
using MNN::Transformer::ChatMessage;
using MNN::Transformer::ChatMessages;

namespace {

class Utf8StreamProcessor {
 public:
  explicit Utf8StreamProcessor(std::function<void(const std::string&)> callback)
      : callback_(std::move(callback)) {}

  void processStream(const char* str, size_t len) {
    buffer_.append(str, len);
    size_t i = 0;
    std::string complete;
    while (i < buffer_.size()) {
      int length = utf8CharLength(static_cast<unsigned char>(buffer_[i]));
      if (length == 0 || i + length > buffer_.size()) break;
      complete.append(buffer_, i, length);
      i += length;
    }
    buffer_ = buffer_.substr(i);
    if (!complete.empty() && callback_) callback_(complete);
  }

  void flush() {
    if (!buffer_.empty() && callback_) {
      callback_(buffer_);
      buffer_.clear();
    }
  }

 private:
  static int utf8CharLength(unsigned char byte) {
    if ((byte & 0x80) == 0) return 1;
    if ((byte & 0xE0) == 0xC0) return 2;
    if ((byte & 0xF0) == 0xE0) return 3;
    if ((byte & 0xF8) == 0xF0) return 4;
    return 0;
  }

  std::string buffer_;
  std::function<void(const std::string&)> callback_;
};

class CallbackStreamBuf : public std::streambuf {
 public:
  explicit CallbackStreamBuf(std::function<void(const char*, size_t)> cb)
      : cb_(std::move(cb)) {}

 protected:
  std::streamsize xsputn(const char* s, std::streamsize n) override {
    if (cb_) cb_(s, static_cast<size_t>(n));
    return n;
  }

 private:
  std::function<void(const char*, size_t)> cb_;
};

struct Session {
  Llm* llm = nullptr;
  std::mutex mutex;
  bool generating = false;
};

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

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL JNI_METHOD(nativeCreate)(JNIEnv* env, jclass,
                                                 jstring jmodel_dir) {
  const std::string model_dir = jstring_to_std(env, jmodel_dir);
  if (model_dir.empty()) {
    throw_java(env, "java/lang/IllegalArgumentException", "model dir empty");
    return 0;
  }
  LOGI("createLLM %s", model_dir.c_str());
  auto* session = new Session();
  session->llm = Llm::createLLM(model_dir);
  if (session->llm == nullptr) {
    LOGE("createLLM returned null");
    delete session;
    return 0;
  }
  if (!session->llm->load()) {
    LOGE("llm->load() failed");
    Llm::destroy(session->llm);
    delete session;
    return 0;
  }
  return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL JNI_METHOD(nativeDestroy)(JNIEnv* /*env*/, jclass,
                                                 jlong handle) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr) return;
  std::lock_guard<std::mutex> lock(session->mutex);
  if (session->generating) {
    LOGE("nativeDestroy while generating");
    return;
  }
  if (session->llm != nullptr) {
    Llm::destroy(session->llm);
    session->llm = nullptr;
  }
  delete session;
}

JNIEXPORT void JNICALL JNI_METHOD(nativeSetConfig)(JNIEnv* env, jclass,
                                                   jlong handle,
                                                   jstring jconfig_json) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr || session->llm == nullptr) return;
  std::lock_guard<std::mutex> lock(session->mutex);
  const std::string cfg = jstring_to_std(env, jconfig_json);
  if (!cfg.empty()) {
    session->llm->set_config(cfg);
  }
}

JNIEXPORT void JNICALL JNI_METHOD(nativeReset)(JNIEnv* /*env*/, jclass,
                                               jlong handle) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr || session->llm == nullptr) return;
  std::lock_guard<std::mutex> lock(session->mutex);
  session->llm->reset();
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
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr || session->llm == nullptr) {
    throw_java(env, "java/lang/IllegalStateException", "session not loaded");
    return;
  }

  std::lock_guard<std::mutex> lock(session->mutex);
  if (session->generating) {
    throw_java(env, "java/lang/IllegalStateException", "already generating");
    return;
  }
  session->generating = true;

  jclass cb_class = env->GetObjectClass(jcallback);
  jmethodID on_token = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");

  ChatMessages messages;
  if (jmessages != nullptr) {
    const jsize n = env->GetArrayLength(jmessages);
    for (jsize i = 0; i + 1 < n; i += 2) {
      jstring jrole = static_cast<jstring>(env->GetObjectArrayElement(jmessages, i));
      jstring jcontent = static_cast<jstring>(env->GetObjectArrayElement(jmessages, i + 1));
      messages.emplace_back(jstring_to_std(env, jrole), jstring_to_std(env, jcontent));
      env->DeleteLocalRef(jrole);
      env->DeleteLocalRef(jcontent);
    }
  }

  int generated = 0;
  Utf8StreamProcessor processor([&](const std::string& piece) {
    if (piece.empty()) return;
    jstring jpiece = env->NewStringUTF(piece.c_str());
    env->CallVoidMethod(jcallback, on_token, jpiece);
    env->DeleteLocalRef(jpiece);
    generated++;
  });

  CallbackStreamBuf stream_buf([&](const char* s, size_t len) {
    processor.processStream(s, len);
  });
  std::ostream os(&stream_buf);

  // A finished turn leaves the context in NORMAL_FINISHED / MAX_TOKENS_FINISHED,
  // and the next response() then decodes nothing. MNN's own Android demo applies
  // the same local reset because it links a prebuilt libMNN.so runtime.
  {
    auto* ctx = session->llm->getContext();
    if (ctx != nullptr && ctx->status != MNN::Transformer::LlmStatus::RUNNING) {
      const_cast<MNN::Transformer::LlmContext*>(ctx)->status =
          MNN::Transformer::LlmStatus::RUNNING;
    }
  }

  try {
    // `end_with` is written to the stream verbatim when generation hits a stop
    // token. MNN defaults it to "\n" for nullptr, which would surface as a
    // bogus newline-only "response". Pass an empty string instead so a stopped
    // generation contributes no text at all.
    session->llm->response(messages, &os, "", max_new_tokens);
    processor.flush();
  } catch (const std::exception& e) {
    session->generating = false;
    throw_java(env, "java/lang/IllegalStateException", e.what());
    return;
  } catch (...) {
    session->generating = false;
    throw_java(env, "java/lang/IllegalStateException", "mnn generate failed");
    return;
  }
  session->generating = false;

  if (jmetrics_out != nullptr) {
    const auto* ctx = session->llm->getContext();
    jlong metrics[5] = {
        ctx != nullptr ? ctx->prompt_len : 0,
        ctx != nullptr ? ctx->gen_seq_len : generated,
        ctx != nullptr ? ctx->prefill_us : 0,
        ctx != nullptr ? ctx->decode_us : 0,
        ctx != nullptr ? ctx->ttfa_us : 0,
    };
    env->SetLongArrayRegion(jmetrics_out, 0, 5, metrics);
  }
}

JNIEXPORT jstring JNICALL JNI_METHOD(nativeVersion)(JNIEnv* env, jclass) {
  return env->NewStringUTF(MNN_VERSION);
}

}  // extern "C"
