#include "mnn_chat_core.hpp"

#include <mutex>
#include <ostream>
#include <streambuf>
#include <utility>

#include "llm/llm.hpp"

namespace droidllm {
namespace mnnchat {

namespace {

using MNN::Transformer::ChatMessages;
using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;

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

}  // namespace

// --- Utf8StreamProcessor ---

void Utf8StreamProcessor::processStream(const char* str, size_t len) {
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

void Utf8StreamProcessor::flush() {
  if (!buffer_.empty() && callback_) {
    callback_(buffer_);
    buffer_.clear();
  }
}

int Utf8StreamProcessor::utf8CharLength(unsigned char byte) {
  if ((byte & 0x80) == 0) return 1;
  if ((byte & 0xE0) == 0xC0) return 2;
  if ((byte & 0xF0) == 0xE0) return 3;
  if ((byte & 0xF8) == 0xF0) return 4;
  return 0;
}

// --- ChatSession ---

struct ChatSession::Impl {
  Llm* llm = nullptr;
  std::mutex mutex;
  bool generating = false;
};

ChatSession::ChatSession() : impl_(new Impl()) {}

ChatSession::~ChatSession() {
  if (impl_ == nullptr) return;
  std::lock_guard<std::mutex> lock(impl_->mutex);
  if (impl_->generating) return;  // leak rather than UAF
  if (impl_->llm != nullptr) {
    Llm::destroy(impl_->llm);
    impl_->llm = nullptr;
  }
}

bool ChatSession::Destroy() {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  if (impl_->generating) return false;
  if (impl_->llm != nullptr) {
    Llm::destroy(impl_->llm);
    impl_->llm = nullptr;
  }
  return true;
}

void ChatSession::RequestCancel() {
  // Intentionally no mutex: Generate() holds impl_->mutex for the whole turn,
  // and the decode loop reads ctx->status without that lock (see generate.cpp
  // USER_CANCEL check). A plain store is enough for a single-word stop flag.
  if (impl_->llm == nullptr) return;
  auto* ctx = impl_->llm->getContext();
  if (ctx != nullptr) {
    const_cast<MNN::Transformer::LlmContext*>(ctx)->status = LlmStatus::USER_CANCEL;
  }
}

std::unique_ptr<ChatSession> ChatSession::Create(const std::string& config_path,
                                                 const std::string& config_json) {
  if (config_path.empty()) return nullptr;
  std::unique_ptr<ChatSession> session(new ChatSession());
  session->impl_->llm = Llm::createLLM(config_path);
  if (session->impl_->llm == nullptr) return nullptr;
  // Llm::load() snapshots backend_type / thread_num when it builds the runtime
  // (llm.cpp: `config.type = backend_type_convert(mConfig->backend_type())`),
  // so a set_config() issued after load() only changes sampling — the chosen
  // backend silently stays whatever config.json said. MNN's own Android demo
  // applies the config at the same point (llm_session.cpp LlmSession::Load()).
  if (!config_json.empty()) {
    session->impl_->llm->set_config(config_json);
  }
  if (!session->impl_->llm->load()) {
    Llm::destroy(session->impl_->llm);
    session->impl_->llm = nullptr;
    return nullptr;
  }
  return session;
}

void ChatSession::SetConfig(const std::string& config_json) {
  if (impl_->llm == nullptr || config_json.empty()) return;
  std::lock_guard<std::mutex> lock(impl_->mutex);
  impl_->llm->set_config(config_json);
}

void ChatSession::Reset() {
  if (impl_->llm == nullptr) return;
  std::lock_guard<std::mutex> lock(impl_->mutex);
  impl_->llm->reset();
}

bool ChatSession::Generate(const std::vector<std::string>& flat_messages,
                           int max_new_tokens,
                           const TokenCallback& on_token,
                           GenerateMetrics* metrics,
                           std::string* error) {
  if (impl_->llm == nullptr) {
    if (error) *error = "session not loaded";
    return false;
  }

  std::lock_guard<std::mutex> lock(impl_->mutex);
  if (impl_->generating) {
    if (error) *error = "already generating";
    return false;
  }
  impl_->generating = true;

  ChatMessages messages;
  for (size_t i = 0; i + 1 < flat_messages.size(); i += 2) {
    messages.emplace_back(flat_messages[i], flat_messages[i + 1]);
  }

  int generated = 0;
  Utf8StreamProcessor processor([&](const std::string& piece) {
    if (piece.empty()) return;
    if (on_token) on_token(piece);
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
    auto* ctx = impl_->llm->getContext();
    if (ctx != nullptr && ctx->status != LlmStatus::RUNNING) {
      const_cast<MNN::Transformer::LlmContext*>(ctx)->status = LlmStatus::RUNNING;
    }
  }

  try {
    // `end_with` is written to the stream verbatim when generation hits a stop
    // token. MNN defaults it to "\n" for nullptr, which would surface as a
    // bogus newline-only "response". Pass an empty string instead so a stopped
    // generation contributes no text at all.
    impl_->llm->response(messages, &os, "", max_new_tokens);
    processor.flush();
  } catch (const std::exception& e) {
    impl_->generating = false;
    if (error) *error = e.what();
    return false;
  } catch (...) {
    impl_->generating = false;
    if (error) *error = "mnn generate failed";
    return false;
  }
  impl_->generating = false;

  {
    auto* ctx = impl_->llm->getContext();
    if (ctx != nullptr && ctx->status == LlmStatus::USER_CANCEL) {
      if (error) *error = "cancelled";
      return false;
    }
  }

  if (metrics != nullptr) {
    const auto* ctx = impl_->llm->getContext();
    metrics->prompt_tokens = ctx != nullptr ? ctx->prompt_len : 0;
    metrics->generated_tokens = ctx != nullptr ? ctx->gen_seq_len : generated;
    metrics->prefill_us = ctx != nullptr ? ctx->prefill_us : 0;
    metrics->decode_us = ctx != nullptr ? ctx->decode_us : 0;
    metrics->ttfa_us = ctx != nullptr ? ctx->ttfa_us : 0;
  }
  return true;
}

const char* MnnVersion() { return MNN_VERSION; }

}  // namespace mnnchat
}  // namespace droidllm
