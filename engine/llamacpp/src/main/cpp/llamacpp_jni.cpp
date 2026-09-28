#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "llamacpp_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define JNI_METHOD(name) \
  Java_io_github_pisces312_droidllm_engine_llamacpp_LlamaCppNative_##name

namespace {

struct Session {
  llama_model* model = nullptr;
  llama_context* ctx = nullptr;
  llama_sampler* smpl = nullptr;
  llama_batch batch{};
  bool batch_valid = false;
  int n_len = 0;
  int n_cur = 0;
  int prompt_tokens = 0;
  std::string utf8_cache;
  std::mutex mutex;
};

std::mutex g_init_mutex;
bool g_backend_ready = false;

void ensure_backend() {
  std::lock_guard<std::mutex> lock(g_init_mutex);
  if (!g_backend_ready) {
    llama_backend_init();
    g_backend_ready = true;
  }
}

void log_callback(ggml_log_level level, const char* text, void* /*user_data*/) {
  if (text == nullptr) return;
  switch (level) {
    case GGML_LOG_LEVEL_ERROR:
      LOGE("%s", text);
      break;
    case GGML_LOG_LEVEL_WARN:
    case GGML_LOG_LEVEL_INFO:
    case GGML_LOG_LEVEL_DEBUG:
    default:
      LOGI("%s", text);
      break;
  }
}

// ggml_abort defaults to fprintf(stderr) which never reaches logcat.
void abort_log_callback(const char* message) {
  LOGE("ggml_abort: %s", message != nullptr ? message : "(null)");
}

void terminate_log_callback() {
  try {
    auto eptr = std::current_exception();
    if (eptr) {
      try {
        std::rethrow_exception(eptr);
      } catch (const std::exception& ex) {
        LOGE("std::terminate: %s", ex.what());
      } catch (...) {
        LOGE("std::terminate: unknown C++ exception");
      }
    } else {
      LOGE("std::terminate: no current exception (abort/terminate)");
    }
  } catch (...) {
  }
  std::abort();
}

bool is_valid_utf8(const char* s) {
  if (s == nullptr) return true;
  const auto* bytes = reinterpret_cast<const unsigned char*>(s);
  while (*bytes != 0x00) {
    int num = 0;
    if ((*bytes & 0x80) == 0x00) {
      num = 1;
    } else if ((*bytes & 0xE0) == 0xC0) {
      num = 2;
    } else if ((*bytes & 0xF0) == 0xE0) {
      num = 3;
    } else if ((*bytes & 0xF8) == 0xF0) {
      num = 4;
    } else {
      return false;
    }
    bytes += 1;
    for (int i = 1; i < num; ++i) {
      if ((*bytes & 0xC0) != 0x80) return false;
      bytes += 1;
    }
  }
  return true;
}

void batch_clear(llama_batch& batch) {
  batch.n_tokens = 0;
}

void batch_add(llama_batch& batch, llama_token token, llama_pos pos,
               bool logits) {
  const int i = batch.n_tokens;
  batch.token[i] = token;
  batch.pos[i] = pos;
  batch.n_seq_id[i] = 1;
  batch.seq_id[i][0] = 0;
  batch.logits[i] = logits ? 1 : 0;
  batch.n_tokens++;
}

void throw_java(JNIEnv* env, const char* class_name, const char* message) {
  jclass clazz = env->FindClass(class_name);
  if (clazz != nullptr) {
    env->ThrowNew(clazz, message);
    env->DeleteLocalRef(clazz);
  }
}

std::string token_to_piece(const llama_vocab* vocab, llama_token token) {
  char buf[256];
  const int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
  if (n <= 0) return "";
  return std::string(buf, static_cast<size_t>(n));
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL JNI_METHOD(nativeInit)(JNIEnv* /*env*/, jclass) {
  ensure_backend();
  llama_log_set(log_callback, nullptr);
  ggml_set_abort_callback(abort_log_callback);
  std::set_terminate(terminate_log_callback);
  LOGI("nativeInit: abort/terminate hooks installed");
}

JNIEXPORT jstring JNICALL JNI_METHOD(nativeSystemInfo)(JNIEnv* env, jclass) {
  ensure_backend();
  return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL JNI_METHOD(nativeLoad)(
    JNIEnv* env, jclass, jstring jmodel_path, jint n_ctx, jint n_threads) {
  ensure_backend();
  const char* path = env->GetStringUTFChars(jmodel_path, nullptr);
  if (path == nullptr) return 0;

  LOGI("Loading model from %s", path);
  auto* session = new Session();

  llama_model_params mparams = llama_model_default_params();
  session->model = llama_model_load_from_file(path, mparams);
  env->ReleaseStringUTFChars(jmodel_path, path);
  if (session->model == nullptr) {
    LOGE("llama_model_load_from_file failed");
    delete session;
    return 0;
  }

  llama_context_params cparams = llama_context_default_params();
  cparams.n_ctx = static_cast<uint32_t>(std::max(256, n_ctx));
  const int threads = std::max(1, n_threads);
  cparams.n_threads = threads;
  cparams.n_threads_batch = threads;
  session->ctx = llama_init_from_model(session->model, cparams);
  if (session->ctx == nullptr) {
    LOGE("llama_init_from_model failed");
    llama_model_free(session->model);
    delete session;
    return 0;
  }

  const int max_tokens = std::max(256, n_ctx);
  session->batch = llama_batch_init(max_tokens, 0, 1);
  session->batch_valid = true;

  auto sparams = llama_sampler_chain_default_params();
  sparams.no_perf = true;
  session->smpl = llama_sampler_chain_init(sparams);
  llama_sampler_chain_add(session->smpl, llama_sampler_init_top_k(40));
  llama_sampler_chain_add(session->smpl, llama_sampler_init_top_p(0.95f, 1));
  llama_sampler_chain_add(session->smpl, llama_sampler_init_temp(0.7f));
  llama_sampler_chain_add(session->smpl,
                          llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

  LOGI("Model loaded, ctx=%p", session->ctx);
  return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL JNI_METHOD(nativeFree)(JNIEnv* /*env*/, jclass,
                                              jlong handle) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr) return;
  std::lock_guard<std::mutex> lock(session->mutex);
  if (session->smpl != nullptr) {
    llama_sampler_free(session->smpl);
    session->smpl = nullptr;
  }
  if (session->batch_valid) {
    llama_batch_free(session->batch);
    session->batch_valid = false;
  }
  if (session->ctx != nullptr) {
    llama_free(session->ctx);
    session->ctx = nullptr;
  }
  if (session->model != nullptr) {
    llama_model_free(session->model);
    session->model = nullptr;
  }
  delete session;
}

JNIEXPORT void JNICALL JNI_METHOD(nativeResetSampler)(
    JNIEnv* env, jclass, jlong handle, jint top_k, jfloat top_p, jfloat temp,
    jlong seed) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr) return;
  std::lock_guard<std::mutex> lock(session->mutex);
  if (session->smpl != nullptr) {
    llama_sampler_free(session->smpl);
    session->smpl = nullptr;
  }
  auto sparams = llama_sampler_chain_default_params();
  sparams.no_perf = true;
  session->smpl = llama_sampler_chain_init(sparams);
  if (temp <= 0.0f || top_k <= 1) {
    llama_sampler_chain_add(session->smpl, llama_sampler_init_greedy());
  } else {
    llama_sampler_chain_add(session->smpl,
                            llama_sampler_init_top_k(std::max(1, top_k)));
    llama_sampler_chain_add(session->smpl,
                            llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(session->smpl, llama_sampler_init_temp(temp));
    const auto s = seed == 0L
                       ? static_cast<uint32_t>(LLAMA_DEFAULT_SEED)
                       : static_cast<uint32_t>(seed);
    llama_sampler_chain_add(session->smpl, llama_sampler_init_dist(s));
  }
}

JNIEXPORT jint JNICALL JNI_METHOD(nativePrefill)(
    JNIEnv* env, jclass, jlong handle, jstring jprompt, jint max_new_tokens) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr || session->ctx == nullptr || session->model == nullptr) {
    throw_java(env, "java/lang/IllegalStateException", "session not loaded");
    return -1;
  }
  std::lock_guard<std::mutex> lock(session->mutex);

  const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
  if (prompt == nullptr) return -1;
  const std::string text(prompt);
  env->ReleaseStringUTFChars(jprompt, prompt);
  LOGI("prefill: text_len=%d max_new=%d", (int)text.size(), max_new_tokens);
  {
    std::string hex;
    const size_t n = std::min<size_t>(text.size(), 160);
    char tmp[8];
    for (size_t i = 0; i < n; ++i) {
      snprintf(tmp, sizeof(tmp), "%02x", (unsigned char)text[i]);
      hex += tmp;
    }
    LOGI("prefill: prompt_hex=%s", hex.c_str());
  }

  const llama_vocab* vocab = llama_model_get_vocab(session->model);
  const bool add_special = true;
  const bool parse_special = true;
  // Buffer-size probe: llama_tokenize returns a negative required count when
  // n_tokens_max is too small (including the nullptr/0 probe call).
  int32_t n_tokens_needed = llama_tokenize(
      vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0,
      add_special, parse_special);
  if (n_tokens_needed < 0) {
    n_tokens_needed = -n_tokens_needed;
  }
  if (n_tokens_needed <= 0) {
    LOGE("tokenize produced 0 tokens (text_len=%d)", (int)text.size());
    throw_java(env, "java/lang/IllegalArgumentException", "tokenize failed");
    return -1;
  }
  std::vector<llama_token> tokens(static_cast<size_t>(n_tokens_needed));
  const int32_t n_tokens = llama_tokenize(
      vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(),
      n_tokens_needed, add_special, parse_special);
  if (n_tokens <= 0) {
    LOGE("tokenize fill failed (ret=%d, needed=%d, text_len=%d)", n_tokens,
         n_tokens_needed, (int)text.size());
    throw_java(env, "java/lang/IllegalArgumentException", "tokenize failed");
    return -1;
  }
  LOGI("prefill: tokenized n=%d needed=%d", n_tokens, n_tokens_needed);
  {
    std::string ids;
    for (int32_t i = 0; i < n_tokens && i < 32; ++i) {
      char tmp[16];
      snprintf(tmp, sizeof(tmp), "%d ", tokens[static_cast<size_t>(i)]);
      ids += tmp;
    }
    LOGI("prefill: token_ids=%s", ids.c_str());
  }

  const auto n_ctx = static_cast<int>(llama_n_ctx(session->ctx));
  session->n_len = std::max(1, max_new_tokens);
  if (static_cast<int>(n_tokens) + session->n_len > n_ctx) {
    session->n_len = std::max(1, n_ctx - static_cast<int>(n_tokens) - 1);
    LOGE("max_new_tokens clamped to %d (n_ctx=%d, prompt=%d)", session->n_len,
         n_ctx, n_tokens);
  }

  batch_clear(session->batch);
  for (int i = 0; i < n_tokens; ++i) {
    batch_add(session->batch, tokens[static_cast<size_t>(i)], i, i == n_tokens - 1);
  }

  llama_memory_clear(llama_get_memory(session->ctx), true);
  LOGI("prefill: llama_decode start n_tokens=%d", n_tokens);
  int decode_rc = -1;
  try {
    decode_rc = llama_decode(session->ctx, session->batch);
  } catch (const std::exception& ex) {
    LOGE("prefill: llama_decode threw: %s", ex.what());
    throw_java(env, "java/lang/IllegalStateException", "llama_decode prefill threw");
    return -1;
  } catch (...) {
    LOGE("prefill: llama_decode threw unknown");
    throw_java(env, "java/lang/IllegalStateException", "llama_decode prefill threw");
    return -1;
  }
  if (decode_rc != 0) {
    LOGE("prefill: llama_decode failed rc=%d", decode_rc);
    throw_java(env, "java/lang/IllegalStateException", "llama_decode prefill failed");
    return -1;
  }
  LOGI("prefill: llama_decode ok");

  session->prompt_tokens = n_tokens;
  session->n_cur = n_tokens;
  session->utf8_cache.clear();
  return n_tokens;
}

JNIEXPORT jstring JNICALL JNI_METHOD(nativeNextToken)(JNIEnv* env, jclass,
                                                      jlong handle) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr || session->ctx == nullptr || session->smpl == nullptr) {
    return nullptr;
  }
  std::lock_guard<std::mutex> lock(session->mutex);
  if (session->n_cur - session->prompt_tokens >= session->n_len) {
    return nullptr;
  }

  const auto* model = llama_get_model(session->ctx);
  const auto* vocab = llama_model_get_vocab(model);
  const llama_token new_token_id =
      llama_sampler_sample(session->smpl, session->ctx, -1);
  if (llama_vocab_is_eog(vocab, new_token_id)) {
    return nullptr;
  }

  session->utf8_cache += token_to_piece(vocab, new_token_id);
  jstring result = nullptr;
  if (is_valid_utf8(session->utf8_cache.c_str())) {
    result = env->NewStringUTF(session->utf8_cache.c_str());
    session->utf8_cache.clear();
  } else {
    result = env->NewStringUTF("");
  }

  batch_clear(session->batch);
  batch_add(session->batch, new_token_id, session->n_cur, true);
  session->n_cur += 1;
  try {
    if (llama_decode(session->ctx, session->batch) != 0) {
      LOGE("next: llama_decode failed tok=%d n_cur=%d", new_token_id, session->n_cur);
      return nullptr;
    }
  } catch (const std::exception& ex) {
    LOGE("next: llama_decode threw: %s", ex.what());
    return nullptr;
  } catch (...) {
    LOGE("next: llama_decode threw unknown");
    return nullptr;
  }
  return result;
}

JNIEXPORT void JNICALL JNI_METHOD(nativeClearKv)(JNIEnv* /*env*/, jclass,
                                                 jlong handle) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr || session->ctx == nullptr) return;
  std::lock_guard<std::mutex> lock(session->mutex);
  llama_memory_clear(llama_get_memory(session->ctx), true);
  session->n_cur = 0;
  session->prompt_tokens = 0;
  session->utf8_cache.clear();
}

JNIEXPORT jint JNICALL JNI_METHOD(nativePromptTokens)(JNIEnv* /*env*/, jclass,
                                                      jlong handle) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr) return 0;
  std::lock_guard<std::mutex> lock(session->mutex);
  return session->prompt_tokens;
}

JNIEXPORT jint JNICALL JNI_METHOD(nativeGeneratedTokens)(JNIEnv* /*env*/,
                                                         jclass,
                                                         jlong handle) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr) return 0;
  std::lock_guard<std::mutex> lock(session->mutex);
  return std::max(0, session->n_cur - session->prompt_tokens);
}

/**
 * messages: [role, content, role, content, ...]
 * Returns model chat-template formatted prompt via llama_chat_apply_template.
 */
JNIEXPORT jstring JNICALL JNI_METHOD(nativeApplyChatTemplate)(
    JNIEnv* env, jclass, jlong handle, jobjectArray jmessages) {
  auto* session = reinterpret_cast<Session*>(handle);
  if (session == nullptr || session->model == nullptr) {
    throw_java(env, "java/lang/IllegalStateException", "session not loaded");
    return nullptr;
  }
  std::lock_guard<std::mutex> lock(session->mutex);

  std::vector<llama_chat_message> msgs;
  std::vector<std::string> roles;
  std::string system_holder;
  std::string first_user_holder;
  std::vector<std::string> contents;
  if (jmessages != nullptr) {
    const jsize n = env->GetArrayLength(jmessages);
    roles.reserve(static_cast<size_t>(n / 2));
    contents.reserve(static_cast<size_t>(n / 2));
    for (jsize i = 0; i + 1 < n; i += 2) {
      jstring jrole = static_cast<jstring>(env->GetObjectArrayElement(jmessages, i));
      jstring jcontent =
          static_cast<jstring>(env->GetObjectArrayElement(jmessages, i + 1));
      const char* role_c = env->GetStringUTFChars(jrole, nullptr);
      const char* content_c = env->GetStringUTFChars(jcontent, nullptr);
      roles.emplace_back(role_c != nullptr ? role_c : "user");
      contents.emplace_back(content_c != nullptr ? content_c : "");
      env->ReleaseStringUTFChars(jrole, role_c);
      env->ReleaseStringUTFChars(jcontent, content_c);
      env->DeleteLocalRef(jrole);
      env->DeleteLocalRef(jcontent);
    }
    for (size_t i = 0; i < roles.size(); ++i) {
      llama_chat_message m{};
      m.role = roles[i].c_str();
      m.content = contents[i].c_str();
      msgs.push_back(m);
    }
  }

  const char* tmpl = llama_model_chat_template(session->model, nullptr);
  const int32_t needed = llama_chat_apply_template(
      tmpl, msgs.data(), static_cast<int32_t>(msgs.size()), true, nullptr, 0);
  if (needed <= 0) {
    // Fallback: simple concatenation.
    std::string fallback;
    for (size_t i = 0; i < roles.size(); ++i) {
      fallback += roles[i];
      fallback += ": ";
      fallback += contents[i];
      fallback += "\n";
    }
    fallback += "assistant:";
    LOGI("chat_template: fallback len=%d", (int)fallback.size());
    return env->NewStringUTF(fallback.c_str());
  }
  std::string buf(static_cast<size_t>(needed), '\0');
  const int32_t written = llama_chat_apply_template(
      tmpl, msgs.data(), static_cast<int32_t>(msgs.size()), true, &buf[0],
      static_cast<int32_t>(buf.size()));
  if (written <= 0) {
    LOGE("chat_template: written=%d needed=%d -> empty", written, needed);
    return env->NewStringUTF("");
  }
  buf.resize(static_cast<size_t>(written));
  LOGI("chat_template: written=%d needed=%d", written, needed);
  return env->NewStringUTF(buf.c_str());
}

}  // extern "C"
