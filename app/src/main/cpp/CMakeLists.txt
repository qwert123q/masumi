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

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama" EXCLUDE_FROM_ALL)
find_package(Threads REQUIRED)

set(MTMD_DIR "${LLAMA_DIR}/tools/mtmd")

add_library(masumi_mtmd STATIC
    "${MTMD_DIR}/mtmd.cpp"
    "${MTMD_DIR}/mtmd-audio.cpp"
    "${MTMD_DIR}/mtmd-image.cpp"
    "${MTMD_DIR}/mtmd-helper.cpp"
    "${MTMD_DIR}/clip.cpp"
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
