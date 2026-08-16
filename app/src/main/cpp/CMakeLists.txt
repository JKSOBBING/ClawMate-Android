cmake_minimum_required(VERSION 3.22.1)
project(clawmate_local_model)

# ============================================================================
# ClawMate Android — local-model native adapter (Phase 10).
#
# Port of the HarmonyOS `entry/src/main/cpp/CMakeLists.txt`, which builds a
# `mnnllm` N-API library from `napi_init.cpp` + `HIAIModelManager.cpp` and links
# MNN + HiAI NNRT. Neither MNN-for-Android nor the HiAI/OHOS N-API runtime is
# available (or legally copyable) in this Android worktree, and the Harmony
# `llm.hpp` dependency (`MNN/expr/*`) cannot be satisfied on Android without
# vendoring a large MNN Android build.
#
# Therefore this library is a **truthful stub**: it exposes the same logical
# surface that the product needs (status / load / unload) but without linking
# any model runtime, and returns typed NOT_CONFIGURED / UNSUPPORTED results.
# It compiles with the Android NDK toolchain and documents exactly what a real
# adapter would need:
#   - libMNN.so (+ MNN include headers) built for each target ABI, and
#   - the `Llm`/`LocalModel` C++ adapter mirroring llm.hpp / napi_init.cpp.
# ============================================================================

# The single, honest stub that exposes status/load/unload over JNI.
add_library(clawmate_local_model SHARED local_model_jni.cpp)

# No external model libraries are linked: adding `MNN`, `hiai`, `llm` here
# intentionally fails to resolve until real Android MNN prebuilts are provided.

find_library(log-lib log)
target_include_directories(clawmate_local_model PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(clawmate_local_model PUBLIC ${log-lib})

# Keep symbols small and avoid accidental hiding of our JNI exports.
set_target_properties(clawmate_local_model PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED YES
    PREFIX "lib"
)
