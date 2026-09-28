#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <pthread.h>

#include "llama.h"

static void abort_cb(const char* msg) {
  fprintf(stderr, "ggml_abort: %s\n", msg != nullptr ? msg : "(null)");
  fflush(stderr);
}

static void log_cb(ggml_log_level level, const char* text, void*) {
  if (text == nullptr) return;
  fputs(text, level == GGML_LOG_LEVEL_ERROR ? stderr : stdout);
}

int main(int argc, char** argv) {
  if (argc < 2) {
    fprintf(stderr, "usage: %s <model.gguf> [prompt]\n", argv[0]);
    return 2;
  }
  const char* model_path = argv[1];
  std::string prompt_storage = "hello";
  if (argc > 2) {
    if (argv[2][0] == '@') {
      FILE* f = fopen(argv[2] + 1, "rb");
      if (f == nullptr) {
        fprintf(stderr, "repro: cannot open prompt file\n");
        return 2;
      }
      char buf[4096];
      size_t n = fread(buf, 1, sizeof(buf) - 1, f);
      fclose(f);
      buf[n] = 0;
      prompt_storage.assign(buf, n);
    } else {
      prompt_storage = argv[2];
    }
  }
  const char* prompt = prompt_storage.c_str();

  setvbuf(stdout, nullptr, _IONBF, 0);
  setvbuf(stderr, nullptr, _IONBF, 0);
  llama_log_set(log_cb, nullptr);
  ggml_set_abort_callback(abort_cb);
  fprintf(stderr, "repro: load %s\n", model_path);

  llama_model_params mparams = llama_model_default_params();
  llama_model* model = llama_load_model_from_file(model_path, mparams);
  if (model == nullptr) {
    fprintf(stderr, "repro: model load failed\n");
    return 1;
  }
  fprintf(stderr, "repro: model loaded\n");

  llama_context_params cparams = llama_context_default_params();
  cparams.n_ctx = 2048;
  cparams.n_batch = 2048;
  cparams.n_ubatch = 512;
  cparams.n_threads = 4;
  cparams.n_threads_batch = 4;
  llama_context* ctx = llama_new_context_with_model(model, cparams);
  if (ctx == nullptr) {
    fprintf(stderr, "repro: context failed\n");
    llama_free_model(model);
    return 1;
  }
  fprintf(stderr, "repro: context ready n_ctx=%d\n", (int)llama_n_ctx(ctx));

  const llama_vocab* vocab = llama_model_get_vocab(model);
  const bool add_special = true;
  const bool parse_special = true;
  std::string text(prompt);
  if (text == "CHATML") {
    // Same path as the app: one system + one user message through model template.
    const char* tmpl = llama_model_chat_template(model, nullptr);
    std::vector<const char*> roles = {"system", "user"};
    std::vector<const char*> contents = {
        "You are a helpful assistant.",
        "hello",
    };
    if (argc > 3) {
      contents[1] = argv[3];
    }
    std::vector<llama_chat_message> msgs;
    for (size_t i = 0; i < roles.size(); ++i) {
      llama_chat_message m{};
      m.role = roles[i];
      m.content = contents[i];
      msgs.push_back(m);
    }
    int32_t needed_t = llama_chat_apply_template(
        tmpl, msgs.data(), (int32_t)msgs.size(), true, nullptr, 0);
    fprintf(stderr, "repro: chat_template needed=%d\n", needed_t);
    if (needed_t <= 0) {
      fprintf(stderr, "repro: chat_template failed\n");
      return 1;
    }
    std::string buf((size_t)needed_t, '\0');
    int32_t written = llama_chat_apply_template(
        tmpl, msgs.data(), (int32_t)msgs.size(), true, &buf[0], needed_t);
    if (written <= 0) {
      fprintf(stderr, "repro: chat_template write failed\n");
      return 1;
    }
    buf.resize((size_t)written);
    text = buf;
    fprintf(stderr, "repro: chat_template written=%d\n", written);
  }
  int32_t needed = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                                  nullptr, 0, add_special, parse_special);
  if (needed < 0) needed = -needed;
  if (needed <= 0) {
    fprintf(stderr, "repro: tokenize probe failed %d\n", needed);
    return 1;
  }
  std::vector<llama_token> tokens((size_t)needed);
  int32_t n_tokens = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                                    tokens.data(), needed, add_special, parse_special);
  if (n_tokens <= 0) {
    fprintf(stderr, "repro: tokenize fill failed %d\n", n_tokens);
    return 1;
  }
  fprintf(stderr, "repro: tokenized n=%d\n", n_tokens);

  // Match the app: sampler exists before decode; batch capacity is n_ctx not n_tokens.
  auto sparams = llama_sampler_chain_default_params();
  sparams.no_perf = true;
  llama_sampler* smpl = llama_sampler_chain_init(sparams);
  llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
  llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
  llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
  llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
  fprintf(stderr, "repro: sampler ready\n");

  llama_batch batch = llama_batch_init(2048, 0, 1);
  for (int32_t i = 0; i < n_tokens; ++i) {
    batch.token[i] = tokens[(size_t)i];
    batch.pos[i] = i;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = (i == n_tokens - 1) ? 1 : 0;
    batch.n_tokens++;
  }

  fprintf(stderr, "repro: llama_decode start n=%d\n", n_tokens);
  llama_memory_clear(llama_get_memory(ctx), true);
  // Optional: run decode on a small-stack pthread like Kotlin Dispatchers.Default (~1MB).
  int rc = -1;
  struct DecodeArg { llama_context* c; llama_batch* b; int out; };
  auto decode_fn = [](void* p) -> void* {
    auto* a = static_cast<DecodeArg*>(p);
    a->out = llama_decode(a->c, *a->b);
    return nullptr;
  };
  const char* stack_kb = getenv("REPRO_STACK_KB");
  if (stack_kb != nullptr && stack_kb[0] != 0) {
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, (size_t)atol(stack_kb) * 1024);
    DecodeArg arg{ctx, &batch, -1};
    pthread_t th;
    fprintf(stderr, "repro: decode on pthread stack=%sKB\n", stack_kb);
    if (pthread_create(&th, &attr, decode_fn, &arg) != 0) {
      fprintf(stderr, "repro: pthread_create failed\n");
      return 1;
    }
    pthread_join(th, nullptr);
    rc = arg.out;
    pthread_attr_destroy(&attr);
  } else {
    rc = llama_decode(ctx, batch);
  }
  fprintf(stderr, "repro: llama_decode rc=%d\n", rc);

  llama_sampler_free(smpl);
  llama_batch_free(batch);
  llama_free(ctx);
  llama_free_model(model);
  fprintf(stderr, "repro: done\n");
  return rc == 0 ? 0 : 1;
}
