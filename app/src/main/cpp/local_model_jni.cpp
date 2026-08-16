// ============================================================================
// ClawMate Android — local-model native adapter (Phase 10, stub).
//
// Honest port of the HarmonyOS MNN/HiAI local-model N-API (`napi_init.cpp`,
// `HIAIModelManager.*`, `llm.hpp`). On Harmony those functions drive real
// on-device inference via MNN + HiAI NNRT; on Android neither MNN-for-Android
// nor the OHOS N-API/HiAI runtime is present, so this file links **no** model
// runtime and exposes the same logical surface as a typed stub:
//
//   nativeStatus()   -> a typed code string ("UNSUPPORTED"/"NATIVE_LIBRARY_MISSING")
//   nativeLoad(...)  -> a typed result ("UNSUPPORTED:NATIVE_LIBRARY_MISSING:...")
//   nativeUnload()   -> "OK" (no native state to release)
//   nativeSelfCheck()-> a stable magic value to confirm the .so loaded
//
// It NEVER fabricates model output and NEVER returns a READY/"loaded" success
// for a real model, because the native inference library is genuinely absent.
// A future adapter must link libMNN built for each ABI plus an `Llm`-equivalent
// C++ adapter; until then the Kotlin side maps every native result to typed
// NOT_CONFIGURED / UNSUPPORTED states.
// ============================================================================

#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "ClawMateLocalModel"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Stable, typed result tokens shared with the Kotlin bridge.
constexpr const char* kStatusUnsupported = "UNSUPPORTED";
constexpr const char* kMissingLibraryToken = "NATIVE_LIBRARY_MISSING";
constexpr const char* kOkToken = "OK";

// Why the stub cannot load: no Android MNN build is linked in this worktree.
std::string missingLibraryReason() {
    return std::string(kMissingLibraryToken)
        + ":libMNN.SO_AND_MNN_INCLUDES_NOT_PROVIDED:no_android_mnn_llm_adapter";
}

}  // namespace

// ----------------------------------------------------------------- Java entry
extern "C" JNIEXPORT jstring JNICALL
Java_com_clawmate_app_localmodel_LocalModelNative_nativeStatus(JNIEnv* env, jobject) {
    LOGI("LocalModelNative.nativeStatus() -> UNSUPPORTED (no native inference runtime)");
    return env->NewStringUTF(kStatusUnsupported);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_clawmate_app_localmodel_LocalModelNative_nativeLoad(JNIEnv* env, jobject, jstring) {
    // A real bridge would call Llm::createLLM(configPath) + load(). Without the
    // Android MNN library that cannot run, so we return a typed UNSUPPORTED.
    LOGI("LocalModelNative.nativeLoad() -> UNSUPPORTED (native lib missing)");
    return env->NewStringUTF(missingLibraryReason().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_clawmate_app_localmodel_LocalModelNative_nativeUnload(JNIEnv* env, jobject) {
    // No native state exists in the stub; unload is a no-op success.
    return env->NewStringUTF(kOkToken);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_clawmate_app_localmodel_LocalModelNative_nativeSelfCheck(JNIEnv* env, jobject) {
    // Returns a stable magic value so the bridge can confirm the .so actually
    // loaded (JNI wiring is real even though inference is not available).
    return 0xC14D4D4LL;  // "CLAMM"
}
