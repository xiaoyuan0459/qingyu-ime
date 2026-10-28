cmake_minimum_required(VERSION 3.22)
project(qingyu_hw_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 使用 third_party 目录里的 ncnn Android 预编译(静态库)
set(NCNN_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../third_party/ncnn-android/${ANDROID_ABI})

if(NOT EXISTS ${NCNN_ROOT})
    message(FATAL_ERROR "ncnn-android 找不到: ${NCNN_ROOT}")
endif()

set(ncnn_DIR ${NCNN_ROOT}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

# 单字手写分类器：OFL 字体合成语料训练，27,664 类，int8 约 8 MB。
# 模型资产在 assets/handwriting/qingyu-hw-image-v1/。
add_library(qingyu_hw_jni SHARED
    ${CMAKE_CURRENT_LIST_DIR}/qingyu_hw_jni.cc
    ${CMAKE_CURRENT_LIST_DIR}/preprocess.c
)
target_include_directories(qingyu_hw_jni PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_link_libraries(qingyu_hw_jni
    PRIVATE
        ncnn
        android
        log
)

# 自研 rime 引擎（librime 1.11.2 + 自写 JNI 绑定）
# 产出 libqingyu_rime.so，替代原先闭源的 libyuyanime.so。
# 详见同目录 qingyu_rime/CMakeLists.txt 与 imesdk/docs/自研native层接口契约.md
add_subdirectory(qingyu_rime)
