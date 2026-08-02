cmake_minimum_required(VERSION 3.22.1)

project("voiceshield_core")

# ==============================================================================
# 1. CẤU HÌNH THƯ MỤC HEADER (.h)
# ==============================================================================
# Khai báo thư mục chứa file .h của dự án và thư viện AI
include_directories(
        ${CMAKE_SOURCE_DIR}
        ${CMAKE_SOURCE_DIR}/include
)

# ==============================================================================
# 2. KHAI BÁO THƯ VIỆN AI INFERENCE ENGINE (TENSORFLOW LITE C/C++ API)
# ==============================================================================
# Tự động load file .so theo kiến trúc CPU (arm64-v8a, x86_64, v.v.)
add_library(tensorflowlite SHARED IMPORTED)
set_target_properties(
        tensorflowlite
        PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libtensorflowlite_jni.so
)

# ==============================================================================
# 3. TẠO THƯ VIỆN CHÍNH (VOICESHIELD_CORE)
# ==============================================================================
add_library(
        voiceshield_core
        SHARED
        native-lib.cpp
        RingBuffer.cpp
        DSP.cpp
        ModelRunner.cpp  # ➕ Đã thêm file quản lý AI Model vào đây
)

# ==============================================================================
# 4. TÌM CÁC THƯ VIỆN HỆ THỐNG CỦA ANDROID NDK
# ==============================================================================
# Thư viện ghi log Android (__android_log_print)
find_library(
        log-lib
        log
)

# Thư viện AAssetManager (Đọc file model từ thư mục assets)
find_library(
        android-lib
        android
)

# ==============================================================================
# 5. LIÊN KẾT TẤT CẢ THƯ VIỆN VÀO VOICESHIELD_CORE
# ==============================================================================
target_link_libraries(
        voiceshield_core
        ${log-lib}
        ${android-lib}
        tensorflowlite
)