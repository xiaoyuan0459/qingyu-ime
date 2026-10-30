# =============================================================================
# 轻语输入法 · 自研 native 层（libqingyu_rime.so）
#
# 产出物实现 `RimeSession.kt` 的 native 边界，静态链接一份**未修改的上游
# librime**及其依赖。
#
# 与旧预编译库的差别：旧库是 librime 的私有分叉（带 AssociateRear 补丁与包名白名单），
# 本工程直接使用 pristine 上游源码；升级需验证 C API 与只读 ReverseDb 边界。
#
# 为什么不用 librime 自带的 find_package 找依赖
# ---------------------------------------------
# librime 的 cmake/Find*.cmake 期望依赖已**安装**到某前缀（头文件位于
# `<prefix>/include/<name>/`）。这里改为直接把各依赖当源码子目录编成静态库，
# 并把 librime 期望的变量指过去：省掉一轮 install，且 fresh clone 后一次 cmake 即可。
#
# 依赖获取方式见 MIGRATION.md。
# =============================================================================

cmake_minimum_required(VERSION 3.22)
project(qingyu_rime LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# 引擎始终按**优化**方式编译，即使用 gradle 的 debug 变体。
#
# 原因：AGP 会给 debug 变体传 `-DCMAKE_BUILD_TYPE=Debug`（即 -O0），而 librime 的主要
# 工作是上千万词条的表/棱镜查找，未优化版本在真机上慢到会让"验证主链路"失去意义。
# 用 RelWithDebInfo 而不是 Release：保留 -g 与帧指针，仍可用 ndk-stack 之类的工具定位
# native 崩溃，同时拿到 -O2 与 -DNDEBUG。
#
# 注意是 CACHE + FORCE：命令行传入的 -DCMAKE_BUILD_TYPE 已被写进 cache，普通 set 覆盖不了。
set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "构建类型（本项目固定为 RelWithDebInfo）" FORCE)

# ------------------------------------------------------------------ 目录
#
# 本文件位于 imesdk/src/main/cpp/qingyu_rime/，third_party 在 imesdk/ 下，
# 故需向上四级。

set(QINGYU_THIRD_PARTY "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party")
set(LIBRIME_ROOT     "${QINGYU_THIRD_PARTY}/librime")
set(LIBRIME_DEPS     "${LIBRIME_ROOT}/deps")
# Boost 按标准布局存放：<root>/include/boost/*.hpp（FindBoost 按此约定探测版本）
set(BOOST_ROOT       "${QINGYU_THIRD_PARTY}/boost/include")

foreach(required_dir "${LIBRIME_ROOT}" "${LIBRIME_DEPS}" "${BOOST_ROOT}")
  if(NOT EXISTS "${required_dir}")
    message(FATAL_ERROR
      "构建依赖缺失: ${required_dir}\n"
      "librime 1.11.2 与 Boost 1.87 头文件的获取方式见 MIGRATION.md。")
  endif()
endforeach()

# ------------------------------------------------------------------ 依赖构建开关
#
# 这些 option 必须在 add_subdirectory 之前设定，否则子项目会各自取默认值。

# 必须写成 CACHE 变量：子项目里的 option(BUILD_SHARED_LIBS ...) 在未设 CMP0077 时
# 会清掉同名普通变量并改用 cache 值，照着写会让 OpenCC 变成动态库。
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)   # 全部编静态库，最终链进 .so

# librime
set(BUILD_TEST        OFF CACHE BOOL "" FORCE)
set(BUILD_SAMPLE      OFF CACHE BOOL "" FORCE)
set(BUILD_DATA        OFF CACHE BOOL "" FORCE)
set(BUILD_SEPARATE_LIBS OFF CACHE BOOL "" FORCE)
# librime 的 glog 日志。排查「会话创建即 SIGSEGV」期间临时开过 ON，现已定位并关闭，原因：
#   - `Kernel.resetIme()`（词库更新后重建引擎）会在同一进程里 stop → start，
#     而 glog 的 `InitGoogleLogging` 有 `CHECK(!logging_initiated)`，第二次调用直接 abort。
#     即「开着 glog 就必然在词库重载时崩」。
#   - 体积：glog 随源码静态编入库中。
# 需要再开时改成 ON，并同步给 nativeStart 传 logDir（见 jni.cc 的说明）。
set(ENABLE_LOGGING OFF CACHE BOOL "" FORCE)
set(WITH_GFLAGS OFF CACHE BOOL "" FORCE)
set(WITH_GTEST OFF CACHE BOOL "" FORCE)
set(WITH_SYMBOLIZE OFF CACHE BOOL "" FORCE)
# Do not let glog's optional stacktrace backend discover the Linux host's libunwind.
# Android's C++ exception unwinding remains supplied by the NDK target runtime.
set(WITH_UNWIND none CACHE STRING "glog stacktrace backend" FORCE)
# glog 会 include(CTest)，而 BUILD_TESTING 默认 ON，会把 *_unittest.cc 一起编进来
# （那些文件依赖 Linux 的 glob.h，Android 上没有）。
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(GLOG_ROOT "${LIBRIME_DEPS}/glog")
if(EXISTS "${GLOG_ROOT}/CMakeLists.txt")
  add_subdirectory(${GLOG_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/glog-build EXCLUDE_FROM_ALL)
  set(Glog_INCLUDE_PATH "${GLOG_ROOT}/src" CACHE PATH "" FORCE)
  set(Glog_LIBRARY glog)
endif()
set(ENABLE_EXTERNAL_PLUGINS OFF CACHE BOOL "" FORCE)

# leveldb
set(LEVELDB_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(LEVELDB_BUILD_BENCHMARKS  OFF CACHE BOOL "" FORCE)
set(LEVELDB_INSTALL           OFF CACHE BOOL "" FORCE)

# yaml-cpp
set(YAML_CPP_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_TOOLS   OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_CONTRIB OFF CACHE BOOL "" FORCE)
set(YAML_CPP_INSTALL       OFF CACHE BOOL "" FORCE)
set(YAML_CPP_FORMAT_SOURCE OFF CACHE BOOL "" FORCE)

# OpenCC
set(ENABLE_GTEST      OFF CACHE BOOL "" FORCE)
set(ENABLE_BENCHMARK  OFF CACHE BOOL "" FORCE)
set(BUILD_DOCUMENTATION OFF CACHE BOOL "" FORCE)

# ------------------------------------------------------------------ Boost
#
# librime 只在 Linux 上 find_package(Boost ... REQUIRED COMPONENTS regex)；
# 其余平台走非 REQUIRED 分支，且源码实际只用 6 个 header-only 头文件
# （algorithm/string、interprocess/*、uuid/*），因此**不需要编译 Boost**。

include_directories(${BOOST_ROOT})
set(Boost_INCLUDE_DIR  "${BOOST_ROOT}" CACHE PATH "" FORCE)
set(Boost_INCLUDE_DIRS "${BOOST_ROOT}" CACHE PATH "" FORCE)

# OpenCC 的数据目录在配置阶段就会 `find_package(PythonInterp REQUIRED)`（用于把
# 词典源编译成 .ocd2）。我们不使用那批预编译词典（运行时直接加载 assets 里的
# 文本词典），但配置阶段仍需 Python 存在，故先探测并给出可读的报错。
if(NOT PYTHON_EXECUTABLE)
  find_program(PYTHON_EXECUTABLE NAMES python3 python python.exe python3.exe)
endif()
if(NOT PYTHON_EXECUTABLE)
  message(FATAL_ERROR
    "未找到 Python；OpenCC 的构建脚本在配置阶段需要它。\n"
    "可通过 -DPYTHON_EXECUTABLE=<path> 显式指定。")
endif()

# ------------------------------------------------------------------ 依赖源码补丁
#
# librime 的 deps/ 在仓库外（不入库），上游把其中某些依赖锁在较老版本上，
# 而 NDK r30 的 clang 21 + C++17 会直接拒绝它们的写法。补丁无法用 git 保存，
# 因此放在这里自动重放（脚本幂等），保证重新克隆 librime 后仍能一次构建成功。
# 详见 tools/patch-opencc-deps.py 的说明。

execute_process(
  COMMAND "${PYTHON_EXECUTABLE}"
          "${CMAKE_CURRENT_LIST_DIR}/../../../../../tools/patch-opencc-deps.py"
          "${LIBRIME_ROOT}"
  RESULT_VARIABLE _patch_result
  OUTPUT_VARIABLE _patch_output
  ERROR_VARIABLE  _patch_error
)
if(_patch_output)
  message(STATUS "${_patch_output}")
endif()
if(NOT _patch_result EQUAL 0)
  message(FATAL_ERROR "依赖补丁脚本执行失败：${_patch_error}")
endif()

# ------------------------------------------------------------------ 第三方依赖
#
# ⚠️ marisa 必须用 **librime 自带的那份**（deps/marisa-trie），不能用 OpenCC 内嵌的
# deps/marisa-0.2.6 —— 两者虽然都把版本号写成 0.2.6，**源码并不相同**
# （sort.h、bit-vector.cc 与多个头文件都有差异），librime 的 deps/marisa-trie 更新。
#
# `*.prism.bin` 正是用 librime 那份 marisa 写出来的；若拿 OpenCC 的旧版去读，
# 会在真正加载方案时崩溃（本项目实测：SIGSEGV in memcpy，触发条件是 schema 里出现
# 两个 table_translator，其中 cn_en 的 `dictionary: ""` 会走到读 prism 的路径）。
#
# 顺序：先引入 librime 的 marisa（产生 target `marisa`），再让 OpenCC 复用它
# （USE_SYSTEM_MARISA=ON）。OpenCC 的 src/CMakeLists.txt 里
# `target_link_libraries(libopencc marisa)` 是硬编码的 target 名，正好指向我们这份。

set(YAMLCPP_ROOT "${LIBRIME_DEPS}/yaml-cpp")
set(LEVELDB_ROOT "${LIBRIME_DEPS}/leveldb")
set(OPENCC_ROOT  "${LIBRIME_DEPS}/opencc")
set(MARISA_ROOT  "${LIBRIME_DEPS}/marisa-trie")

add_subdirectory(${MARISA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/marisa-build EXCLUDE_FROM_ALL)

# 让 OpenCC 跳过自带的 marisa：LIBMARISA 预置为非空值，find_library 便不再搜索，
# 随后的 target_link_libraries(libopencc marisa) 会链到上面那个 target。
set(USE_SYSTEM_MARISA ON CACHE BOOL "" FORCE)
set(LIBMARISA marisa CACHE STRING "" FORCE)

add_subdirectory(${YAMLCPP_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/yamlcpp-build EXCLUDE_FROM_ALL)
add_subdirectory(${LEVELDB_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/leveldb-build EXCLUDE_FROM_ALL)
add_subdirectory(${OPENCC_ROOT}  ${CMAKE_CURRENT_BINARY_DIR}/opencc-build  EXCLUDE_FROM_ALL)

# leveldb 在开启 clang thread-safety 时，把 `-Werror -Wthread-safety` 以 **PUBLIC**
# 方式挂在自己身上（deps/leveldb/CMakeLists.txt 的 target_compile_options(... PUBLIC ...)），
# 于是顺着链接关系传染给 rime-static 以及本模块。
#
# 后果：librime 编译 gear/simplifier.cc 时会包含 OpenCC 的头，而 OpenCC 1.1.7 里用了
# 已在 C++17 弃用的 std::iterator —— 那个“弃用”警告在 -Werror 下直接变成编译错误。
#
# 只清掉**接口导出**（INTERFACE），leveldb 自身的编译选项不变。
set_property(TARGET leveldb PROPERTY INTERFACE_COMPILE_OPTIONS "")

# ------------------------------------------------------------------ OpenCC 头文件垫片
#
# librime 以 `<opencc/Config.hpp>` 形式包含 OpenCC 头（期望的是"已安装"布局），
# 而 OpenCC 源码内部用相对包含（`#include "Common.hpp"`）。此处按安装后的布局
# 复制一份头文件，避免改动第三方源码。

set(OPENCC_SHIM "${CMAKE_CURRENT_BINARY_DIR}/opencc-include")
file(MAKE_DIRECTORY "${OPENCC_SHIM}/opencc")
file(GLOB _opencc_headers "${OPENCC_ROOT}/src/*.hpp" "${OPENCC_ROOT}/src/*.h")
file(COPY ${_opencc_headers} DESTINATION "${OPENCC_SHIM}/opencc")
# 配置期由 opencc_config.h.in 生成的头（Common.hpp 以 `#include "opencc_config.h"`
# 相对包含它）。它不在 src/ 里，glob 取不到，必须单独带过来。
# add_subdirectory(opencc) 已在上方执行，configure_file 在此刻已产出该文件。
set(_opencc_generated_header "${CMAKE_CURRENT_BINARY_DIR}/opencc-build/src/opencc_config.h")
if(EXISTS "${_opencc_generated_header}")
  file(COPY "${_opencc_generated_header}" DESTINATION "${OPENCC_SHIM}/opencc")
else()
  message(FATAL_ERROR
    "未找到 OpenCC 生成的头文件: ${_opencc_generated_header}\n"
    "它由 OpenCC 的 configure_file 产出，缺失说明上面的 add_subdirectory 未生效。")
endif()

# ------------------------------------------------------------------ 告知 librime 依赖位置
#
# librime 的 Find*.cmake 内部是 find_path + find_library；一旦变量已有有效值，
# find_* 便不再搜索。故用 CACHE ... FORCE 显式钉住。
# 注意：库变量可以传 CMake target 名，target_link_libraries 支持这种写法。

set(YamlCpp_INCLUDE_PATH "${YAMLCPP_ROOT}/include" CACHE PATH "" FORCE)
set(YamlCpp_NEW_API      "${YAMLCPP_ROOT}/include" CACHE PATH "" FORCE)
set(YamlCpp_LIBRARY      yaml-cpp)

set(LevelDb_INCLUDE_PATH "${LEVELDB_ROOT}/include" CACHE PATH "" FORCE)
set(LevelDb_LIBRARY      leveldb)

set(Marisa_INCLUDE_PATH  "${MARISA_ROOT}/include" CACHE PATH "" FORCE)
set(Marisa_LIBRARY       marisa)

set(Opencc_INCLUDE_PATH  "${OPENCC_SHIM}" CACHE PATH "" FORCE)
set(Opencc_LIBRARY       libopencc)

# ------------------------------------------------------------------ librime

# 相邻键纠错需要新的 `correct` 拼写类型（排在 abbrev 之后），让精确/简拼路径存在时
# 音节图剪掉纠错切分；vendored librime 不在仓库内，构建前用脚本幂等重放。
# 决策与验证见 docs/adr/0007-纠错切分权重.md。
execute_process(
  COMMAND "${PYTHON_EXECUTABLE}"
          "${CMAKE_CURRENT_LIST_DIR}/../../../../../tools/patch-librime-correction-type.py"
          "${LIBRIME_ROOT}"
  RESULT_VARIABLE _correction_patch_result
  OUTPUT_VARIABLE _correction_patch_output
  ERROR_VARIABLE _correction_patch_error
)
if(_correction_patch_output)
  message(STATUS "${_correction_patch_output}")
endif()
if(NOT _correction_patch_result EQUAL 0)
  message(FATAL_ERROR "librime 纠错拼写类型补丁失败：${_correction_patch_error}")
endif()

add_subdirectory(${LIBRIME_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/librime-build EXCLUDE_FROM_ALL)

# ------------------------------------------------------------------ 本模块

add_library(qingyu_rime SHARED
  ${CMAKE_CURRENT_LIST_DIR}/jni.cc
    ${CMAKE_CURRENT_LIST_DIR}/native_script.cc
  ${CMAKE_CURRENT_LIST_DIR}/association.cc
  ${CMAKE_CURRENT_LIST_DIR}/stroke_lookup.cc
  ${CMAKE_CURRENT_LIST_DIR}/stroke_wildcard.cc
)

target_include_directories(qingyu_rime PRIVATE
  ${LIBRIME_ROOT}/src
  # Internal dictionary headers also require librime's generated build_config.h.
  ${CMAKE_CURRENT_BINARY_DIR}/librime-build/src
  ${LIBRIME_ROOT}/include)

target_link_libraries(qingyu_rime
  PRIVATE
    rime-static
    marisa
    libopencc
    leveldb
    yaml-cpp
    log
    android
)

# 只保留必要的符号，减小体积
target_compile_options(qingyu_rime PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)

# ------------------------------------------------------------------ 词库编译工具
#
# `rime_deployer` 把 `*.dict.yaml` / `*.schema.yaml` 源编成运行时用的
# `build/*.table.bin` / `*.prism.bin`。本工程用它**自己编的 librime** 产出词库，
# 从而不再依赖旧闭源库的私有二进制格式（见 tools/patch-table-bin-for-upstream-librime.py
# 改写头部那 8 字节的来历），也才能启用 `en_dicts/`（中英混输）与 `speller/algebra`。
#
# 它是命令行工具，**不打进 APK**：由 gradle 编出来、adb push 到设备上跑
# （词库编译要跑一次十几分钟，放设备上跑是为了复用已经打通的 Android 工具链，
# 省掉在桌面再配一套 librime 依赖）。
#
# 上游 tools/CMakeLists.txt 给它加了 `RIME_IMPORTS`（Windows dllimport 用），
# 这里静态链接、且非 Windows，不需要那个定义。
add_executable(qingyu_deployer ${LIBRIME_ROOT}/tools/rime_deployer.cc)

target_include_directories(qingyu_deployer PRIVATE
  ${LIBRIME_ROOT}/src
  ${LIBRIME_ROOT}/tools
  # librime 的 `src/rime/build_config.h` 是 configure_file 生成的（它的内部头文件
  # 全都依赖它），落在 librime 的 binary 目录下，故需显式带上。
  # `jni.cc` 只用公开的 `rime_api.h`，所以本模块此前不需要这个路径。
  ${CMAKE_CURRENT_BINARY_DIR}/librime-build/src)

target_link_libraries(qingyu_deployer
  PRIVATE
    rime-static
    marisa
    libopencc
    leveldb
    yaml-cpp
    log
    android)

# ------------------------------------------------------------------ 码空间探针
#
# 读已部署的 prism/table 二进制，导出「哪些码被接受 → 解析成哪些音节」的 TSV，
# 用于把「重编 speller/algebra 后码空间不变」变成可差分校验的事。
# 理由见 probe.cc 头部注释。同样不打进 APK。
add_executable(qingyu_probe ${CMAKE_CURRENT_LIST_DIR}/probe.cc ${CMAKE_CURRENT_LIST_DIR}/native_script.cc)

target_include_directories(qingyu_probe PRIVATE
  ${LIBRIME_ROOT}/src
  # `rime/dict/prism.h` 里 `#include <darts.h>`，该头在 librime 的 include/ 下
  ${LIBRIME_ROOT}/include
  # 同 qingyu_deployer：`rime/build_config.h` 是 configure_file 生成的
  ${CMAKE_CURRENT_BINARY_DIR}/librime-build/src)

target_link_libraries(qingyu_probe
  PRIVATE
    rime-static
    marisa
    libopencc
    leveldb
    yaml-cpp
    log
    android)

# 链接期瘦身。
# 只保留对外可见的 15 个 JNI 入口，静态库符号不外泄。
# 注意：**不要加 -Wl,--gc-sections** —— librime 的模块与各类组件都靠静态初始化器
# （RIME_REGISTER_MODULE / RIME_REGISTER_*) 注册到 Registry，激进地丢弃未被引用的节
# 有把它们一起丢掉的风险，表现为运行期在 `Config::Require("xxx")->Create(...)` 处
# 崩溃。体积收益有限（真正的体积在调试信息里，由 strip 处理），不值得冒这个险。
target_link_options(qingyu_rime PRIVATE
  -Wl,--exclude-libs,ALL
)
