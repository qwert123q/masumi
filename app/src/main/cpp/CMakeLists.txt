cmake_minimum_required(VERSION 3.22.1)
project(masumi_ocr LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

if(ANDROID)
    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        set(MASUMI_NDK_HOST_TAG "darwin-x86_64")
    elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(MASUMI_NDK_HOST_TAG "windows-x86_64")
    else()
        set(MASUMI_NDK_HOST_TAG "linux-x86_64")
    endif()
    set(MASUMI_NDK_GLSLC
        "${CMAKE_ANDROID_NDK}/shader-tools/${MASUMI_NDK_HOST_TAG}/glslc")
    if(NOT EXISTS "${MASUMI_NDK_GLSLC}")
        message(FATAL_ERROR "Android NDK glslc is required for the Vulkan OCR backend")
    endif()
    set(Vulkan_GLSLC_EXECUTABLE "${MASUMI_NDK_GLSLC}"
        CACHE FILEPATH "Android NDK glslc" FORCE)

    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        set(MASUMI_HOST_C_COMPILER "/usr/bin/clang")
        set(MASUMI_HOST_CXX_COMPILER "/usr/bin/clang++")
    else()
        find_program(MASUMI_HOST_C_COMPILER NAMES clang gcc NO_CMAKE_FIND_ROOT_PATH REQUIRED)
        find_program(MASUMI_HOST_CXX_COMPILER NAMES clang++ g++ NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    endif()
    set(MASUMI_VULKAN_HOST_TOOLCHAIN
        "${CMAKE_CURRENT_BINARY_DIR}/masumi-vulkan-host-toolchain.cmake")
    file(WRITE "${MASUMI_VULKAN_HOST_TOOLCHAIN}"
        "set(CMAKE_C_COMPILER \"${MASUMI_HOST_C_COMPILER}\" CACHE FILEPATH \"\" FORCE)\n"
        "set(CMAKE_CXX_COMPILER \"${MASUMI_HOST_CXX_COMPILER}\" CACHE FILEPATH \"\" FORCE)\n"
        "set(CMAKE_MAKE_PROGRAM \"${CMAKE_MAKE_PROGRAM}\" CACHE FILEPATH \"\" FORCE)\n")
    # llama.cpp forwards this list into the host ExternalProject CMake arguments.
    # Pass Ninja explicitly because Android's bundled Ninja is not necessarily on PATH.
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
        "${MASUMI_VULKAN_HOST_TOOLCHAIN};-DCMAKE_MAKE_PROGRAM:FILEPATH=${CMAKE_MAKE_PROGRAM}"
        CACHE STRING "Host toolchain and generator for Vulkan shaders" FORCE)
endif()

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
set(VULKAN_HPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/Vulkan-Hpp")
set(SPIRV_HEADERS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/SPIRV-Headers/include")
if(NOT EXISTS "${VULKAN_HPP_DIR}/vulkan/vulkan.hpp")
    message(FATAL_ERROR "Pinned Vulkan-Hpp headers are required for the Vulkan OCR backend")
endif()
if(NOT EXISTS "${SPIRV_HEADERS_DIR}/spirv/unified1/spirv.hpp")
    message(FATAL_ERROR "Pinned SPIR-V headers are required for the Vulkan OCR backend")
endif()
add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama" EXCLUDE_FROM_ALL)
target_include_directories(ggml-vulkan PRIVATE "${VULKAN_HPP_DIR}" "${SPIRV_HEADERS_DIR}")

if(ANDROID)
    # Some Android vendor Vulkan compilers crash when several compute pipelines are
    # compiled concurrently. Keep the pinned llama.cpp checkout pristine and build
    # an Android-only generated translation unit that serializes pipeline creation.
    set(MASUMI_GGML_VULKAN_SOURCE
        "${LLAMA_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp")
    file(READ "${MASUMI_GGML_VULKAN_SOURCE}" MASUMI_GGML_VULKAN_CONTENT)
    set(MASUMI_VULKAN_PARALLEL_COMPILE_LINE
        "uint32_t N = std::max(1u, std::thread::hardware_concurrency());")
    string(FIND "${MASUMI_GGML_VULKAN_CONTENT}"
        "${MASUMI_VULKAN_PARALLEL_COMPILE_LINE}"
        MASUMI_VULKAN_PARALLEL_COMPILE_OFFSET)
    if(MASUMI_VULKAN_PARALLEL_COMPILE_OFFSET EQUAL -1)
        message(FATAL_ERROR
            "Pinned ggml-vulkan pipeline compile contract changed; review Android serialization patch")
    endif()
    string(REPLACE
        "${MASUMI_VULKAN_PARALLEL_COMPILE_LINE}"
        "uint32_t N = 1u; // Android vendor shader compilers may not be thread-safe."
        MASUMI_GGML_VULKAN_PATCHED_CONTENT
        "${MASUMI_GGML_VULKAN_CONTENT}")
    set(MASUMI_VULKAN_DMMV_SUBGROUP_LINE
        "const bool use_subgroups = device->subgroup_arithmetic && device->architecture != vk_device_architecture::AMD_GCN;")
    string(FIND "${MASUMI_GGML_VULKAN_PATCHED_CONTENT}"
        "${MASUMI_VULKAN_DMMV_SUBGROUP_LINE}"
        MASUMI_VULKAN_DMMV_SUBGROUP_OFFSET)
    if(MASUMI_VULKAN_DMMV_SUBGROUP_OFFSET EQUAL -1)
        message(FATAL_ERROR
            "Pinned ggml-vulkan DMMV subgroup contract changed; review Android compatibility patch")
    endif()
    string(REPLACE
        "${MASUMI_VULKAN_DMMV_SUBGROUP_LINE}"
        "const bool use_subgroups = false; // Avoid Android vendor compiler crashes in DMMV shaders."
        MASUMI_GGML_VULKAN_PATCHED_CONTENT
        "${MASUMI_GGML_VULKAN_PATCHED_CONTENT}")
    set(MASUMI_GGML_VULKAN_PATCHED_SOURCE
        "${CMAKE_CURRENT_BINARY_DIR}/ggml-vulkan-android.cpp")
    file(WRITE "${MASUMI_GGML_VULKAN_PATCHED_SOURCE}"
        "${MASUMI_GGML_VULKAN_PATCHED_CONTENT}")

    get_target_property(MASUMI_GGML_VULKAN_TARGET_SOURCES ggml-vulkan SOURCES)
    list(REMOVE_ITEM MASUMI_GGML_VULKAN_TARGET_SOURCES "ggml-vulkan.cpp")
    list(APPEND MASUMI_GGML_VULKAN_TARGET_SOURCES
        "${MASUMI_GGML_VULKAN_PATCHED_SOURCE}")
    set_property(TARGET ggml-vulkan PROPERTY SOURCES
        "${MASUMI_GGML_VULKAN_TARGET_SOURCES}")
endif()
find_package(Threads REQUIRED)

set(MTMD_DIR "${LLAMA_DIR}/tools/mtmd")
set(MASUMI_MTMD_CLIP_SOURCE "${MTMD_DIR}/clip.cpp")
if(ANDROID)
    file(READ "${MASUMI_MTMD_CLIP_SOURCE}" MASUMI_MTMD_CLIP_CONTENT)
    set(MASUMI_PADDLE_IMAGE_LIMIT_BLOCK
        "get_u32(KEY_IMAGE_MAX_PIXELS, hparams.image_max_pixels);\n\n                        hparams.set_warmup_n_tokens(28*28);")
    string(FIND "${MASUMI_MTMD_CLIP_CONTENT}"
        "${MASUMI_PADDLE_IMAGE_LIMIT_BLOCK}"
        MASUMI_PADDLE_IMAGE_LIMIT_OFFSET)
    if(MASUMI_PADDLE_IMAGE_LIMIT_OFFSET EQUAL -1)
        message(FATAL_ERROR
            "Pinned mtmd PaddleOCR image-limit contract changed; review crop-adaptive token patch")
    endif()
    string(REPLACE
        "${MASUMI_PADDLE_IMAGE_LIMIT_BLOCK}"
        "get_u32(KEY_IMAGE_MAX_PIXELS, hparams.image_max_pixels);\n                        hparams.set_limit_image_tokens(64, 2048);\n\n                        hparams.set_warmup_n_tokens(28*28);"
        MASUMI_MTMD_CLIP_PATCHED_CONTENT
        "${MASUMI_MTMD_CLIP_CONTENT}")
    set(MASUMI_MTMD_CLIP_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/mtmd-clip-android.cpp")
    file(WRITE "${MASUMI_MTMD_CLIP_SOURCE}" "${MASUMI_MTMD_CLIP_PATCHED_CONTENT}")
endif()

add_library(masumi_mtmd STATIC
    "${MTMD_DIR}/mtmd.cpp"
    "${MTMD_DIR}/mtmd-audio.cpp"
    "${MTMD_DIR}/mtmd-image.cpp"
    "${MTMD_DIR}/mtmd-helper.cpp"
    "${MASUMI_MTMD_CLIP_SOURCE}"
    "${MTMD_DIR}/models/cogvlm.cpp"
    "${MTMD_DIR}/models/conformer.cpp"
    "${MTMD_DIR}/models/dotsocr.cpp"
    "${MTMD_DIR}/models/gemma4a.cpp"
    "${MTMD_DIR}/models/gemma4v.cpp"
    "${MTMD_DIR}/models/glm4v.cpp"
    "${MTMD_DIR}/models/hunyuanocr.cpp"
    "${MTMD_DIR}/models/internvl.cpp"
    "${MTMD_DIR}/models/kimivl.cpp"
    "${MTMD_DIR}/models/kimik25.cpp"
    "${MTMD_DIR}/models/nemotron-v2-vl.cpp"
    "${MTMD_DIR}/models/llama4.cpp"
    "${MTMD_DIR}/models/llava.cpp"
    "${MTMD_DIR}/models/minicpmv.cpp"
    "${MTMD_DIR}/models/paddleocr.cpp"
    "${MTMD_DIR}/models/pixtral.cpp"
    "${MTMD_DIR}/models/qwen2vl.cpp"
    "${MTMD_DIR}/models/qwen3vl.cpp"
    "${MTMD_DIR}/models/qwen3a.cpp"
    "${MTMD_DIR}/models/step3vl.cpp"
    "${MTMD_DIR}/models/siglip.cpp"
    "${MTMD_DIR}/models/whisper-enc.cpp"
    "${MTMD_DIR}/models/deepseekocr.cpp"
    "${MTMD_DIR}/models/mobilenetv5.cpp"
    "${MTMD_DIR}/models/youtuvl.cpp"
    "${MTMD_DIR}/models/yasa2.cpp"
)
target_include_directories(masumi_mtmd PUBLIC "${MTMD_DIR}")
target_include_directories(masumi_mtmd PRIVATE "${LLAMA_DIR}" "${LLAMA_DIR}/vendor")
target_compile_options(masumi_mtmd PRIVATE -Wno-cast-qual -Wno-missing-prototypes)
target_link_libraries(masumi_mtmd PUBLIC ggml llama PRIVATE Threads::Threads)

add_library(masumi_ocr SHARED paddle_ocr_jni.cpp)
target_include_directories(masumi_ocr PRIVATE "${MTMD_DIR}")
target_link_libraries(masumi_ocr PRIVATE masumi_mtmd llama ggml android log)
