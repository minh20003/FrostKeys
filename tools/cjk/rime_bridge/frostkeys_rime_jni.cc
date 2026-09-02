// SPDX-License-Identifier: GPL-3.0-only
//
// Narrow FrostKeys JNI boundary for librime.  This source is deliberately
// independent of the Android app checkout: it is compiled only into a reviewed
// ARM64 candidate after both librime and the OpenCC data have passed their own
// provenance gates.  Do not add a generic "execute Rime command" method here.

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <exception>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "rime_api.h"

namespace {

constexpr uint8_t kPacketVersion = 1;
constexpr uint8_t kFlagHasResult = 1u << 0;
constexpr uint8_t kFlagCanPageBackward = 1u << 1;
constexpr uint8_t kFlagCanPageForward = 1u << 2;
constexpr size_t kMaxPacketBytes = 128u * 1024u;
constexpr size_t kMaxTextBytes = 16u * 1024u;
constexpr size_t kMaxCandidates = 64u;
constexpr int kBackspaceKey = 0xff08;
constexpr char kSchemaId[] = "luna_pinyin";

void ThrowJava(JNIEnv* env, const char* class_name, const std::string& message) {
  if (env->ExceptionCheck()) return;
  jclass exception_class = env->FindClass(class_name);
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message.c_str());
    env->DeleteLocalRef(exception_class);
  }
}

std::string RequireUtf8Path(JNIEnv* env, jstring value, const char* label) {
  if (value == nullptr) throw std::invalid_argument(std::string(label) + " is null");
  const char* raw = env->GetStringUTFChars(value, nullptr);
  if (raw == nullptr) throw std::runtime_error(std::string("Could not read ") + label);
  std::string result(raw);
  env->ReleaseStringUTFChars(value, raw);
  if (result.empty() || result.size() > 4096 || result.front() != '/' || result.find('\0') != std::string::npos) {
    throw std::invalid_argument(std::string(label) + " is not an absolute app-private path");
  }
  return result;
}

std::string CopyBoundedRimeText(const char* value, const char* label, bool allow_empty) {
  if (value == nullptr) {
    if (allow_empty) return {};
    throw std::runtime_error(std::string("Rime returned null ") + label);
  }
  const void* terminator = std::memchr(value, '\0', kMaxTextBytes + 1u);
  if (terminator == nullptr) throw std::runtime_error(std::string("Rime ") + label + " exceeds the packet limit");
  const auto size = static_cast<const char*>(terminator) - value;
  if (!allow_empty && size == 0) throw std::runtime_error(std::string("Rime returned empty ") + label);
  return std::string(value, static_cast<size_t>(size));
}

class PacketWriter {
 public:
  PacketWriter() {
    // version, flags, candidate count, page number
    bytes_.reserve(256);
    bytes_.push_back(kPacketVersion);
    bytes_.push_back(0);
    WriteU16(0);
    WriteU32(0);
  }

  void SetFlags(uint8_t flags) { bytes_[1] = flags; }

  void SetCandidateCount(size_t count) {
    if (count > kMaxCandidates || count > std::numeric_limits<uint16_t>::max()) {
      throw std::runtime_error("Rime candidate count exceeds the packet limit");
    }
    bytes_[2] = static_cast<uint8_t>(count & 0xffu);
    bytes_[3] = static_cast<uint8_t>((count >> 8u) & 0xffu);
  }

  void SetPage(int page) {
    if (page < 0) throw std::runtime_error("Rime returned a negative candidate page");
    const uint32_t encoded = static_cast<uint32_t>(page);
    bytes_[4] = static_cast<uint8_t>(encoded & 0xffu);
    bytes_[5] = static_cast<uint8_t>((encoded >> 8u) & 0xffu);
    bytes_[6] = static_cast<uint8_t>((encoded >> 16u) & 0xffu);
    bytes_[7] = static_cast<uint8_t>((encoded >> 24u) & 0xffu);
  }

  void WriteText(const std::string& value, const char* label) {
    if (value.size() > kMaxTextBytes || value.size() > std::numeric_limits<uint16_t>::max()) {
      throw std::runtime_error(std::string("Rime ") + label + " exceeds the packet limit");
    }
    if (bytes_.size() + sizeof(uint16_t) + value.size() > kMaxPacketBytes) {
      throw std::runtime_error("Rime state packet exceeds the packet limit");
    }
    WriteU16(static_cast<uint16_t>(value.size()));
    bytes_.insert(bytes_.end(), value.begin(), value.end());
  }

  const std::vector<uint8_t>& bytes() const { return bytes_; }

 private:
  void WriteU16(uint16_t value) {
    bytes_.push_back(static_cast<uint8_t>(value & 0xffu));
    bytes_.push_back(static_cast<uint8_t>((value >> 8u) & 0xffu));
  }

  void WriteU32(uint32_t value) {
    for (int shift = 0; shift < 32; shift += 8) {
      bytes_.push_back(static_cast<uint8_t>((value >> shift) & 0xffu));
    }
  }

  std::vector<uint8_t> bytes_;
};

struct GlobalRimeRuntime {
  std::mutex mutex;
  RimeApi* api = nullptr;
  bool initialized = false;
  size_t session_count = 0;
  std::string shared_data_dir;
  std::string user_data_dir;
};

GlobalRimeRuntime& Runtime() {
  static GlobalRimeRuntime runtime;
  return runtime;
}

void RequireApi(RimeApi* api) {
  if (api == nullptr || !RIME_API_AVAILABLE(api, setup) || !RIME_API_AVAILABLE(api, initialize) ||
      !RIME_API_AVAILABLE(api, finalize) || !RIME_API_AVAILABLE(api, create_session) ||
      !RIME_API_AVAILABLE(api, destroy_session) || !RIME_API_AVAILABLE(api, process_key) ||
      !RIME_API_AVAILABLE(api, commit_composition) || !RIME_API_AVAILABLE(api, clear_composition) ||
      !RIME_API_AVAILABLE(api, get_commit) || !RIME_API_AVAILABLE(api, free_commit) ||
      !RIME_API_AVAILABLE(api, get_context) || !RIME_API_AVAILABLE(api, free_context) ||
      !RIME_API_AVAILABLE(api, select_candidate_on_current_page) || !RIME_API_AVAILABLE(api, set_option) ||
      !RIME_API_AVAILABLE(api, select_schema) || !RIME_API_AVAILABLE(api, get_version)) {
    throw std::runtime_error("librime does not expose the reviewed Rime 1.16 session API");
  }
}

void InitializeRuntimeLocked(GlobalRimeRuntime& runtime,
                             const std::string& shared_data_dir,
                             const std::string& user_data_dir) {
  if (runtime.initialized) {
    if (runtime.shared_data_dir != shared_data_dir || runtime.user_data_dir != user_data_dir) {
      throw std::runtime_error("librime is global and already bound to another FrostKeys profile");
    }
    return;
  }

  RimeApi* api = rime_get_api();
  RequireApi(api);
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared_data_dir.c_str();
  traits.user_data_dir = user_data_dir.c_str();
  traits.distribution_name = "FrostKeys";
  traits.distribution_code_name = "frostkeys";
  traits.distribution_version = "3.0.0-vn.1";
  traits.app_name = "rime.frostkeys";
  // The reviewed Rime candidate is compiled with logging disabled.  Keep this
  // at error-only as a defensive fallback and never give librime an external
  // log directory or a query/prompt-bearing log sink.
  traits.min_log_level = 2;
  traits.log_dir = "";
  api->setup(&traits);
  api->initialize(nullptr);

  // Deployment compiles only assets already extracted and hash-verified by
  // EngineBundleInstaller.  It can take noticeable time, so Kotlin invokes
  // creation from a background EngineBundleManager executor.
  if (RIME_API_AVAILABLE(api, start_maintenance) && api->start_maintenance(True)) {
    if (RIME_API_AVAILABLE(api, join_maintenance_thread)) api->join_maintenance_thread();
  }

  runtime.api = api;
  runtime.initialized = true;
  runtime.shared_data_dir = shared_data_dir;
  runtime.user_data_dir = user_data_dir;
}

void ReleaseRuntimeLocked(GlobalRimeRuntime& runtime) {
  if (runtime.session_count != 0 || !runtime.initialized) return;
  runtime.api->finalize();
  runtime.api = nullptr;
  runtime.initialized = false;
  runtime.shared_data_dir.clear();
  runtime.user_data_dir.clear();
}

struct FrostKeysRimeSession {
  std::mutex mutex;
  RimeSessionId session_id = 0;
  bool closed = false;
  std::string version;
};

FrostKeysRimeSession* RequireSession(jlong handle) {
  if (handle == 0) throw std::invalid_argument("Rime session handle is empty");
  return reinterpret_cast<FrostKeysRimeSession*>(handle);
}

std::vector<uint8_t> SnapshotLocked(FrostKeysRimeSession& session) {
  GlobalRimeRuntime& runtime = Runtime();
  if (session.closed || !runtime.initialized || runtime.api == nullptr ||
      !runtime.api->find_session(session.session_id)) {
    throw std::runtime_error("Rime session is closed");
  }
  RimeApi* api = runtime.api;

  std::string result;
  RIME_STRUCT(RimeCommit, commit);
  const bool has_result = api->get_commit(session.session_id, &commit) && commit.text != nullptr;
  if (has_result) {
    try {
      result = CopyBoundedRimeText(commit.text, "commit", true);
    } catch (...) {
      api->free_commit(&commit);
      throw;
    }
  }
  if (commit.text != nullptr) api->free_commit(&commit);

  RIME_STRUCT(RimeContext, context);
  const bool has_context = api->get_context(session.session_id, &context);
  std::string preedit;
  std::vector<std::string> candidates;
  int page = 0;
  bool can_page_backward = false;
  bool can_page_forward = false;
  try {
    if (has_context) {
      preedit = CopyBoundedRimeText(context.composition.preedit, "preedit", true);
      if (context.menu.num_candidates < 0 ||
          static_cast<size_t>(context.menu.num_candidates) > kMaxCandidates) {
        throw std::runtime_error("Rime candidate count exceeds the reviewed UI limit");
      }
      if (context.menu.num_candidates > 0 && context.menu.candidates == nullptr) {
        throw std::runtime_error("Rime returned candidates without candidate storage");
      }
      candidates.reserve(static_cast<size_t>(context.menu.num_candidates));
      for (int index = 0; index < context.menu.num_candidates; ++index) {
        candidates.push_back(CopyBoundedRimeText(context.menu.candidates[index].text, "candidate", false));
      }
      page = std::max(context.menu.page_no, 0);
      can_page_backward = page > 0;
      can_page_forward = context.menu.num_candidates > 0 && !context.menu.is_last_page;
    }

    PacketWriter packet;
    uint8_t flags = 0;
    if (has_result && !result.empty()) flags |= kFlagHasResult;
    if (can_page_backward) flags |= kFlagCanPageBackward;
    if (can_page_forward) flags |= kFlagCanPageForward;
    packet.SetFlags(flags);
    packet.SetCandidateCount(candidates.size());
    packet.SetPage(page);
    packet.WriteText(preedit, "preedit");
    packet.WriteText(result, "commit");
    for (const std::string& candidate : candidates) packet.WriteText(candidate, "candidate");
    if (has_context) api->free_context(&context);
    return packet.bytes();
  } catch (...) {
    if (has_context) api->free_context(&context);
    throw;
  }
}

jbyteArray NewByteArray(JNIEnv* env, const std::vector<uint8_t>& bytes) {
  if (bytes.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
    throw std::runtime_error("Rime response does not fit a Java byte array");
  }
  jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
  if (result == nullptr) throw std::runtime_error("Could not allocate Rime state response");
  if (!bytes.empty()) {
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte*>(bytes.data()));
  }
  return result;
}

template <typename Action>
jbyteArray Execute(JNIEnv* env, jlong handle, Action&& action) {
  FrostKeysRimeSession* session = RequireSession(handle);
  std::lock_guard<std::mutex> session_lock(session->mutex);
  std::lock_guard<std::mutex> runtime_lock(Runtime().mutex);
  if (session->closed) throw std::runtime_error("Rime session is closed");
  action(*Runtime().api, session->session_id);
  return NewByteArray(env, SnapshotLocked(*session));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_helium314_keyboard_latin_cjk_RimeNativeBridge_nativeCreate(
    JNIEnv* env,
    jobject /* self */,
    jstring shared_data_directory,
    jstring user_data_directory) {
  try {
    const std::string shared = RequireUtf8Path(env, shared_data_directory, "Rime shared data directory");
    const std::string user = RequireUtf8Path(env, user_data_directory, "Rime user data directory");
    GlobalRimeRuntime& runtime = Runtime();
    std::lock_guard<std::mutex> runtime_lock(runtime.mutex);
    InitializeRuntimeLocked(runtime, shared, user);
    RimeSessionId session_id = runtime.api->create_session();
    if (session_id == 0) throw std::runtime_error("librime could not create a Pinyin session");
    if (!runtime.api->select_schema(session_id, kSchemaId)) {
      runtime.api->destroy_session(session_id);
      ReleaseRuntimeLocked(runtime);
      throw std::runtime_error("The verified Rime bundle has no luna_pinyin schema");
    }
    // Simplified Chinese is the explicit default.  The Rime minimal schema
    // converts traditional dictionary entries through t2s.json; that file and
    // its .ocd2 dependencies must therefore be part of the verified bundle.
    runtime.api->set_option(session_id, "zh_simp", True);
    runtime.api->set_option(session_id, "zh_trad", False);
    runtime.api->set_option(session_id, "zh_tw", False);
    runtime.api->set_option(session_id, "zh_hk", False);
    auto created = std::make_unique<FrostKeysRimeSession>();
    created->session_id = session_id;
    const char* version = runtime.api->get_version();
    created->version = CopyBoundedRimeText(version, "version", false);
    ++runtime.session_count;
    return reinterpret_cast<jlong>(created.release());
  } catch (const std::exception& error) {
    ThrowJava(env, "java/lang/IllegalStateException", error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_helium314_keyboard_latin_cjk_RimeNativeBridge_nativeProcessPinyinKey(
    JNIEnv* env,
    jobject /* self */,
    jlong handle,
    jint key_code) {
  try {
    if (!((key_code >= 'a' && key_code <= 'z') || key_code == '\'')) {
      throw std::invalid_argument("Rime accepts only one normalized Pinyin letter or apostrophe");
    }
    return Execute(env, handle, [key_code](RimeApi& api, RimeSessionId session_id) {
      api.process_key(session_id, key_code, 0);
    });
  } catch (const std::exception& error) {
    ThrowJava(env, "java/lang/IllegalArgumentException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_helium314_keyboard_latin_cjk_RimeNativeBridge_nativeBackspace(
    JNIEnv* env,
    jobject /* self */,
    jlong handle) {
  try {
    return Execute(env, handle, [](RimeApi& api, RimeSessionId session_id) {
      api.process_key(session_id, kBackspaceKey, 0);
    });
  } catch (const std::exception& error) {
    ThrowJava(env, "java/lang/IllegalStateException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_helium314_keyboard_latin_cjk_RimeNativeBridge_nativeSelectCandidate(
    JNIEnv* env,
    jobject /* self */,
    jlong handle,
    jint index) {
  try {
    if (index < 0 || static_cast<size_t>(index) >= kMaxCandidates) {
      throw std::invalid_argument("Rime candidate index is outside the reviewed UI limit");
    }
    return Execute(env, handle, [index](RimeApi& api, RimeSessionId session_id) {
      api.select_candidate_on_current_page(session_id, static_cast<size_t>(index));
    });
  } catch (const std::exception& error) {
    ThrowJava(env, "java/lang/IllegalArgumentException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_helium314_keyboard_latin_cjk_RimeNativeBridge_nativeChangePage(
    JNIEnv* env,
    jobject /* self */,
    jlong handle,
    jboolean backward) {
  try {
    return Execute(env, handle, [backward](RimeApi& api, RimeSessionId session_id) {
      if (!RIME_API_AVAILABLE(&api, change_page)) {
        throw std::runtime_error("librime does not expose candidate paging");
      }
      api.change_page(session_id, backward == JNI_TRUE ? True : False);
    });
  } catch (const std::exception& error) {
    ThrowJava(env, "java/lang/IllegalStateException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_helium314_keyboard_latin_cjk_RimeNativeBridge_nativeCommit(
    JNIEnv* env,
    jobject /* self */,
    jlong handle) {
  try {
    return Execute(env, handle, [](RimeApi& api, RimeSessionId session_id) {
      api.commit_composition(session_id);
    });
  } catch (const std::exception& error) {
    ThrowJava(env, "java/lang/IllegalStateException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_helium314_keyboard_latin_cjk_RimeNativeBridge_nativeReset(
    JNIEnv* env,
    jobject /* self */,
    jlong handle) {
  try {
    return Execute(env, handle, [](RimeApi& api, RimeSessionId session_id) {
      api.clear_composition(session_id);
    });
  } catch (const std::exception& error) {
    ThrowJava(env, "java/lang/IllegalStateException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_helium314_keyboard_latin_cjk_RimeNativeBridge_nativeSetSimplifiedOutput(
    JNIEnv* env,
    jobject /* self */,
    jlong handle,
    jboolean simplified) {
  try {
    return Execute(env, handle, [simplified](RimeApi& api, RimeSessionId session_id) {
      api.clear_composition(session_id);
      api.set_option(session_id, "zh_simp", simplified == JNI_TRUE ? True : False);
      api.set_option(session_id, "zh_trad", simplified == JNI_TRUE ? False : True);
      api.set_option(session_id, "zh_tw", False);
      api.set_option(session_id, "zh_hk", False);
    });
  } catch (const std::exception& error) {
    ThrowJava(env, "java/lang/IllegalStateException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_helium314_keyboard_latin_cjk_RimeNativeBridge_nativeVersion(
    JNIEnv* env,
    jobject /* self */,
    jlong handle) {
  try {
    FrostKeysRimeSession* session = RequireSession(handle);
    std::lock_guard<std::mutex> session_lock(session->mutex);
    if (session->closed || session->version.empty()) throw std::runtime_error("Rime session is closed");
    return env->NewStringUTF(session->version.c_str());
  } catch (const std::exception& error) {
    ThrowJava(env, "java/lang/IllegalStateException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_cjk_RimeNativeBridge_nativeClose(
    JNIEnv* env,
    jobject /* self */,
    jlong handle) {
  try {
    if (handle == 0) return;
    std::unique_ptr<FrostKeysRimeSession> session(RequireSession(handle));
    std::lock_guard<std::mutex> session_lock(session->mutex);
    std::lock_guard<std::mutex> runtime_lock(Runtime().mutex);
    if (session->closed) return;
    session->closed = true;
    GlobalRimeRuntime& runtime = Runtime();
    if (runtime.initialized && runtime.api != nullptr && session->session_id != 0) {
      runtime.api->destroy_session(session->session_id);
    }
    session->session_id = 0;
    if (runtime.session_count == 0) {
      throw std::runtime_error("Rime runtime session accounting underflow");
    }
    --runtime.session_count;
    ReleaseRuntimeLocked(runtime);
  } catch (const std::exception& error) {
    // close() must not crash the IME.  Kotlin has already made its handle
    // unreachable before this call; leave a Java exception only for tests and
    // callers that invoke the native method directly.
    ThrowJava(env, "java/lang/IllegalStateException", error.what());
  }
}
