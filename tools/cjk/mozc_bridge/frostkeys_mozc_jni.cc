// SPDX-License-Identifier: GPL-3.0-only
//
// FrostKeys-owned JNI boundary for the pinned Mozc C++ engine.
//
// This file is intentionally a standalone build input: it is not compiled by
// the Android Gradle project and no copy of it is bundled in the APK yet.  See
// README.md in this directory for the required source-snapshot build step and
// the Kotlin-side lifecycle contract.

#ifdef __ANDROID__

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <utility>

#include "absl/status/statusor.h"
#include "base/system_util.h"
#include "data_manager/data_manager.h"
#include "engine/engine.h"
#include "protocol/commands.pb.h"
#include "session/session_handler.h"

namespace frostkeys::mozc_bridge {
namespace {

// Command protobufs are an internal boundary between the Kotlin adapter and
// this native wrapper.  A keyboard event must be far smaller than this; the
// ceiling prevents a corrupt caller from making native code allocate an
// unbounded buffer.  Candidate pages are returned one at a time by Mozc.
constexpr jsize kMaxCommandBytes = 256 * 1024;

// Mozc owns SystemUtil's profile directory as process-wide state.  This guard
// is deliberately *not* a global SessionHandler: every engine/session lives in
// a FrostKeysMozcContext referenced by one Java long handle.  The guard merely
// prevents two contexts from racing that upstream process-wide configuration.
std::mutex g_profile_directory_mutex;
std::string g_profile_directory;
bool g_context_active = false;

void Throw(JNIEnv* env, const char* exception_class, const char* message) {
  jclass exception = env->FindClass(exception_class);
  if (exception != nullptr) {
    env->ThrowNew(exception, message);
    env->DeleteLocalRef(exception);
  }
}

void ThrowIllegalArgument(JNIEnv* env, const char* message) {
  Throw(env, "java/lang/IllegalArgumentException", message);
}

void ThrowIllegalState(JNIEnv* env, const char* message) {
  Throw(env, "java/lang/IllegalStateException", message);
}

bool ReadUtf8String(JNIEnv* env, jstring source, std::string* destination) {
  if (source == nullptr) {
    ThrowIllegalArgument(env, "Mozc path is required");
    return false;
  }
  const char* chars = env->GetStringUTFChars(source, nullptr);
  if (chars == nullptr) {
    // The VM has already raised OutOfMemoryError when appropriate.
    return false;
  }
  *destination = chars;
  env->ReleaseStringUTFChars(source, chars);
  if (destination->empty()) {
    ThrowIllegalArgument(env, "Mozc path must not be empty");
    return false;
  }
  return true;
}

bool ReadBytes(JNIEnv* env, jbyteArray source, std::string* destination) {
  if (source == nullptr) {
    ThrowIllegalArgument(env, "Mozc command is required");
    return false;
  }
  const jsize size = env->GetArrayLength(source);
  if (size <= 0 || size > kMaxCommandBytes) {
    ThrowIllegalArgument(env, "Mozc command exceeds the allowed size");
    return false;
  }
  destination->assign(static_cast<size_t>(size), '\0');
  env->GetByteArrayRegion(
      source, 0, size, reinterpret_cast<jbyte*>(destination->data()));
  return !env->ExceptionCheck();
}

jbyteArray WriteBytes(JNIEnv* env, const std::string& source) {
  if (source.empty() || source.size() > static_cast<size_t>(kMaxCommandBytes)) {
    ThrowIllegalState(env, "Mozc returned an invalid command response");
    return nullptr;
  }
  jbyteArray destination = env->NewByteArray(static_cast<jsize>(source.size()));
  if (destination == nullptr) return nullptr;
  env->SetByteArrayRegion(
      destination, 0, static_cast<jsize>(source.size()),
      reinterpret_cast<const jbyte*>(source.data()));
  return env->ExceptionCheck() ? nullptr : destination;
}

bool ReserveProfileDirectory(const std::string& profile_directory) {
  std::lock_guard<std::mutex> lock(g_profile_directory_mutex);
  // SystemUtil has no supported reset operation.  Reusing the same private
  // profile after a close is safe; switching the profile inside a process is
  // deliberately rejected rather than risking cross-profile learning data.
  if (g_context_active ||
      (!g_profile_directory.empty() && g_profile_directory != profile_directory)) {
    return false;
  }
  mozc::SystemUtil::SetUserProfileDirectory(profile_directory);
  g_profile_directory = profile_directory;
  g_context_active = true;
  return true;
}

void ReleaseProfileDirectory() {
  std::lock_guard<std::mutex> lock(g_profile_directory_mutex);
  g_context_active = false;
}

std::unique_ptr<mozc::EngineInterface> CreateEngineFromData(
    const std::string& data_file_path) {
  absl::StatusOr<std::unique_ptr<const mozc::DataManager>> data_manager =
      mozc::DataManager::CreateFromFile(data_file_path);
  if (!data_manager.ok()) return nullptr;

  // Never fall back to Engine::CreateEngine() without data: an empty/minimal
  // engine would make a corrupted bundle appear to provide Japanese input.
  // StatusOr owns the unique_ptr returned by DataManager. Transfer that exact
  // data-backed manager into Engine; dereferencing it into a temporary
  // reference would not match Engine::CreateEngine's ownership-taking API.
  auto engine = mozc::Engine::CreateEngine(std::move(*data_manager));
  if (!engine.ok()) return nullptr;
  return *std::move(engine);
}

// The Kotlin codec deliberately exposes only these composition modes. Reject
// DIRECT because this bridge is for the offline Japanese engine, not a hidden
// way to switch Mozc into a bypass mode. New modes need an explicit Kotlin UI,
// codec test, and bridge review before they can be added here.
bool IsAllowedCompositionMode(mozc::commands::CompositionMode mode) {
  switch (mode) {
    case mozc::commands::HIRAGANA:
    case mozc::commands::FULL_KATAKANA:
    case mozc::commands::HALF_KATAKANA:
    case mozc::commands::HALF_ASCII:
    case mozc::commands::FULL_ASCII:
      return true;
    case mozc::commands::DIRECT:
    case mozc::commands::NUM_OF_COMPOSITIONS:
      return false;
  }
  return false;
}

// Do not forward a caller-supplied Input wholesale. Even though the Kotlin
// class has no raw-command API, reconstructing the tiny reviewed subset here
// makes reflection or a future accidental API change unable to issue config,
// reload, history-clear, cloud, or arbitrary SessionCommand operations.
bool CopyAllowedInput(const mozc::commands::Input& parsed_input,
                      uint64_t session_id,
                      mozc::commands::Command* command) {
  const auto input_type = parsed_input.type();
  if (input_type != mozc::commands::Input::SEND_KEY &&
      input_type != mozc::commands::Input::SEND_COMMAND) {
    return false;
  }

  auto* input = command->mutable_input();
  input->set_type(input_type);
  input->set_id(session_id);
  if (input_type == mozc::commands::Input::SEND_KEY) {
    if (!parsed_input.has_key()) return false;
    *input->mutable_key() = parsed_input.key();
    return true;
  }

  if (!parsed_input.has_command()) return false;
  const auto& requested = parsed_input.command();
  auto* allowed = input->mutable_command();
  allowed->set_type(requested.type());
  switch (requested.type()) {
    case mozc::commands::SessionCommand::REVERT:
    case mozc::commands::SessionCommand::SUBMIT:
    case mozc::commands::SessionCommand::CONVERT_PREV_PAGE:
    case mozc::commands::SessionCommand::CONVERT_NEXT_PAGE:
      return true;
    case mozc::commands::SessionCommand::SELECT_CANDIDATE:
      if (!requested.has_id()) return false;
      allowed->set_id(requested.id());
      return true;
    case mozc::commands::SessionCommand::TURN_ON_IME:
      if (!requested.has_composition_mode() ||
          !IsAllowedCompositionMode(requested.composition_mode())) {
        return false;
      }
      allowed->set_composition_mode(requested.composition_mode());
      return true;
    default:
      return false;
  }
}

}  // namespace

/**
 * Owns exactly one Mozc SessionHandler and exactly one Mozc session id.
 *
 * Callers must serialize create/eval/close at the Kotlin adapter boundary.
 * The mutex protects Mozc itself from accidental reentrant native calls; it
 * cannot make a raw jlong safe after a concurrent Java-side delete, hence the
 * Java owner must set its handle to zero while holding its own lifecycle lock.
 */
class FrostKeysMozcContext final {
 public:
  static std::unique_ptr<FrostKeysMozcContext> Create(
      const std::string& profile_directory, const std::string& data_file_path) {
    if (!ReserveProfileDirectory(profile_directory)) return nullptr;

    auto engine = CreateEngineFromData(data_file_path);
    if (!engine) {
      ReleaseProfileDirectory();
      return nullptr;
    }
    auto context = std::unique_ptr<FrostKeysMozcContext>(
        new FrostKeysMozcContext(std::move(engine)));
    if (!context->CreateSession()) {
      context.reset();
      return nullptr;
    }
    return context;
  }

  FrostKeysMozcContext(const FrostKeysMozcContext&) = delete;
  FrostKeysMozcContext& operator=(const FrostKeysMozcContext&) = delete;

  ~FrostKeysMozcContext() { Close(); }

  bool Eval(const std::string& request_bytes, std::string* response_bytes) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!handler_ || session_id_ == 0) return false;

    mozc::commands::Command parsed;
    if (!parsed.ParseFromArray(
            request_bytes.data(), static_cast<int>(request_bytes.size())) ||
        !parsed.IsInitialized() || !parsed.has_input()) {
      return false;
    }

    // The bridge creates/deletes the only session itself. CopyAllowedInput
    // reconstructs only reviewed typing, commit/cancel/select/page and
    // explicit Japanese-mode activation operations; all other client/server
    // commands are rejected rather than passing through this byte boundary.
    mozc::commands::Command command;
    if (!CopyAllowedInput(parsed.input(), session_id_, &command)) return false;
    if (!handler_->EvalCommand(&command) || !command.IsInitialized()) {
      return false;
    }

    const size_t output_size = command.ByteSizeLong();
    if (output_size == 0 || output_size > static_cast<size_t>(kMaxCommandBytes)) {
      return false;
    }
    response_bytes->assign(output_size, '\0');
    return command.SerializeToArray(
        response_bytes->data(), static_cast<int>(output_size));
  }

  std::string DataVersion() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!handler_) return {};
    const auto version = handler_->GetDataVersion();
    return std::string(version.begin(), version.end());
  }

  void Close() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!handler_) return;

    // DELETE_SESSION calls EngineInterface::Sync() in the pinned Mozc source,
    // so learned words are flushed before the context releases native memory.
    if (session_id_ != 0) {
      mozc::commands::Command command;
      command.mutable_input()->set_type(mozc::commands::Input::DELETE_SESSION);
      command.mutable_input()->set_id(session_id_);
      handler_->EvalCommand(&command);
      session_id_ = 0;
    }
    handler_.reset();
    ReleaseProfileDirectory();
  }

 private:
  explicit FrostKeysMozcContext(std::unique_ptr<mozc::EngineInterface> engine)
      : handler_(std::make_unique<mozc::SessionHandler>(std::move(engine))) {}

  bool CreateSession() {
    std::lock_guard<std::mutex> lock(mutex_);
    mozc::commands::Command command;
    command.mutable_input()->set_type(mozc::commands::Input::CREATE_SESSION);
    if (!handler_->EvalCommand(&command) || !command.has_output() ||
        !command.output().has_id()) {
      return false;
    }
    session_id_ = command.output().id();
    return session_id_ != 0;
  }

  std::mutex mutex_;
  std::unique_ptr<mozc::SessionHandler> handler_;
  uint64_t session_id_ = 0;
};

FrostKeysMozcContext* ContextFromHandle(jlong handle) {
  return reinterpret_cast<FrostKeysMozcContext*>(handle);
}

}  // namespace frostkeys::mozc_bridge

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
  return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_helium314_keyboard_latin_cjk_MozcNativeBridge_nativeCreate(
    JNIEnv* env, jobject, jstring profile_directory, jstring data_file_path) {
  std::string profile;
  std::string data_file;
  if (!frostkeys::mozc_bridge::ReadUtf8String(env, profile_directory, &profile) ||
      !frostkeys::mozc_bridge::ReadUtf8String(env, data_file_path, &data_file)) {
    return 0;
  }

  auto context = frostkeys::mozc_bridge::FrostKeysMozcContext::Create(profile, data_file);
  if (!context) {
    // Do not include an app-private path or the contents of a malformed bundle
    // in a release exception/log.
    frostkeys::mozc_bridge::ThrowIllegalState(env, "Mozc engine could not be initialized");
    return 0;
  }
  return reinterpret_cast<jlong>(context.release());
}

JNIEXPORT jbyteArray JNICALL
Java_helium314_keyboard_latin_cjk_MozcNativeBridge_nativeEvalCommand(
    JNIEnv* env, jobject, jlong handle, jbyteArray request) {
  auto* context = frostkeys::mozc_bridge::ContextFromHandle(handle);
  if (context == nullptr) {
    frostkeys::mozc_bridge::ThrowIllegalState(env, "Mozc engine is closed");
    return nullptr;
  }

  std::string request_bytes;
  if (!frostkeys::mozc_bridge::ReadBytes(env, request, &request_bytes)) return nullptr;
  std::string response_bytes;
  if (!context->Eval(request_bytes, &response_bytes)) {
    frostkeys::mozc_bridge::ThrowIllegalState(env, "Mozc command was rejected");
    return nullptr;
  }
  return frostkeys::mozc_bridge::WriteBytes(env, response_bytes);
}

JNIEXPORT jstring JNICALL
Java_helium314_keyboard_latin_cjk_MozcNativeBridge_nativeDataVersion(
    JNIEnv* env, jobject, jlong handle) {
  auto* context = frostkeys::mozc_bridge::ContextFromHandle(handle);
  if (context == nullptr) {
    frostkeys::mozc_bridge::ThrowIllegalState(env, "Mozc engine is closed");
    return nullptr;
  }
  const std::string version = context->DataVersion();
  return env->NewStringUTF(version.c_str());
}

JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_cjk_MozcNativeBridge_nativeClose(
    JNIEnv*, jobject, jlong handle) {
  // Kotlin clears its guarded handle before this call.  A non-zero raw handle
  // is single-use by contract; deleting it twice would be a Java lifecycle bug.
  delete frostkeys::mozc_bridge::ContextFromHandle(handle);
}

}  // extern "C"

#endif  // __ANDROID__
