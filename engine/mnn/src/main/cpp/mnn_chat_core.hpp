#pragma once

#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace droidllm {
namespace mnnchat {

// Complete UTF-8 text pieces (never split mid-character).
using TokenCallback = std::function<void(const std::string& piece)>;

// Mirrors the JNI metrics array: [promptTokens, generatedTokens, prefillUs,
// decodeUs, ttftUs]. See docs/mnn.md §3 for which fields are trustworthy.
struct GenerateMetrics {
  long long prompt_tokens = 0;
  long long generated_tokens = 0;
  long long prefill_us = 0;
  long long decode_us = 0;
  long long ttfa_us = 0;
};

// One MnnEngine session: createLLM -> set_config -> load, then generate.
// Contract (docs/mnn.md): set_config must precede load(); each generate resets
// LlmStatus to RUNNING; end_with is "" so stop contributes no sentinel text.
class ChatSession {
 public:
  // config_path: absolute path to config.json (NOT the directory).
  // config_json: set_config payload applied before load(); empty keeps the
  // model's own config.json values. Returns nullptr on failure.
  static std::unique_ptr<ChatSession> Create(const std::string& config_path,
                                             const std::string& config_json);
  ~ChatSession();

  ChatSession(const ChatSession&) = delete;
  ChatSession& operator=(const ChatSession&) = delete;

  void SetConfig(const std::string& config_json);
  void Reset();

  // Tears down the underlying Llm. Refuses (returns false) while generate is
  // in flight so the decode loop cannot race free. Caller still owns `this`.
  bool Destroy();

  // Ask the decode loop to stop at the next token boundary by setting
  // LlmStatus::USER_CANCEL on the live LlmContext. Safe to call from another
  // thread while Generate() is blocked in response(); does not take the
  // generate mutex (that would wait for the whole turn). The next Generate()
  // resets status to RUNNING as usual.
  void RequestCancel();

  // flat_messages: [role, content, role, content, ...].
  // Blocks until generation finishes. On failure returns false and fills *error.
  // On RequestCancel() mid-turn, returns false with *error "cancelled".
  bool Generate(const std::vector<std::string>& flat_messages,
                int max_new_tokens,
                const TokenCallback& on_token,
                GenerateMetrics* metrics,
                std::string* error);

 private:
  ChatSession();
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

// Byte-level UTF-8 splitter used by generate callbacks. Public so host
// regression can exercise it without loading a model.
class Utf8StreamProcessor {
 public:
  explicit Utf8StreamProcessor(TokenCallback callback)
      : callback_(std::move(callback)) {}

  void processStream(const char* str, size_t len);
  void flush();

 private:
  static int utf8CharLength(unsigned char byte);

  std::string buffer_;
  TokenCallback callback_;
};

const char* MnnVersion();

}  // namespace mnnchat
}  // namespace droidllm
