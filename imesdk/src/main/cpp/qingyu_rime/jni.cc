/*
 * 轻语输入法 —— 自研 native 层（librime 绑定）
 *
 * 本文件是 `RimeSession.kt` 中 `private external fun` 的原生实现，
 * 会话入口统一在这里；只读笔画反查隔离在 stroke_lookup.cc。
 *
 * 与旧预编译库的关系
 * ------------------
 * 旧 `libyuyanime.so` 是 librime 的一个**私有分叉**（内嵌 58 个 `rime::AssociateRear`
 * 等符号，并带包名白名单校验），本文件不复用它任何代码或符号命名，仅使用上游
 * librime 1.11.2 的 C API 操作会话。笔画反查依赖固定版本的 ReverseDb C++ 类型，
 * 升级时需单独核对。接口契约见 `imesdk/docs/自研native层接口契约.md`。
 *
 * 索引约定
 * --------
 * 上层传入的候选下标一律是**全局下标**（不是页内下标）。这要求 schema 的
 * `menu/page_size` 足够大，让全部常见候选落在同一页内，见 assets 中的 schema 配置。
 *
 * 线程模型
 * --------
 * librime 的会话不是线程安全的，会话入口使用同一把互斥锁。笔画查询只读独立映射，
 * 不接触任何会话。调用频率最高的
 * 是 `nativeContext`（每次按键一次），锁竞争可忽略。
 */

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>
#include <rime_api.h>
#include <unistd.h>
#include "association.h"
#include "stroke_lookup.h"
#include "stroke_wildcard.h"
#include "native_script.h"

// librime 的「按键名 → 键码」查表函数，实现在 src/rime/key_table.cc。
//
// 它的声明在 librime 的 src/rime/key_table.h，但那个头文件**无条件** `#include
// <X11/keysym.h>`（Android 没有这个头），因此这里不包含它，只前置声明需要的那一个
// 符号。函数位于全局命名空间、C++ 链接（nm 可见 `_Z20RimeGetKeycodeByNamePKc`），
// 静态链接到 rime-static，可直接调用。
int RimeGetKeycodeByName(const char* name);

// ------------------------------------------------------------------ 日志
//
// 本工程的 librime 编译时关掉了 glog（ENABLE_LOGGING=OFF，省体积也避免记录用户输入），
// 于是库内部不会输出任何诊断信息。代价是引擎"静默失败"时无从下手，因此这里补一层
// 只覆盖 JNI 边界的日志：
//   - 正常事件走 DEBUG（`adb logcat -s QingyuRime:V` 可见）
//   - 异常事件（返回失败、读不到上下文、键未被消费）走 WARN/ERROR，默认就能看到
// 这样既能排查问题，又不会在正常使用时刷日志。

#define QY_LOG_TAG "QingyuRime"
#define QY_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, QY_LOG_TAG, __VA_ARGS__)
#define QY_LOGW(...) __android_log_print(ANDROID_LOG_WARN, QY_LOG_TAG, __VA_ARGS__)
#define QY_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, QY_LOG_TAG, __VA_ARGS__)

namespace {

using std::string;
using std::vector;

// ------------------------------------------------------------------ 全局状态

/** 当前 rime 会话 id；0 表示引擎尚未启动。 */
RimeSessionId g_session = 0;

/** librime 会话非线程安全，所有 native 入口共用此锁串行化。 */
std::mutex g_mutex;

/**
 * 本 native 层的分发版本号，写入 librime 的 `RimeTraits.distribution_version`。
 *
 * 它与所链接的 librime 版本是两件事：librime 侧版本由其自身的 `RIME_VERSION`
 * 宏（1.11.2）维护，并会写进 `<user_data_dir>/installation.yaml` 的 `rime_version`。
 * 这里用独立常量而不是引入 librime 的宏 —— 那个宏只定义在 librime 自己的
 * 目录作用域内，且两者的版本节奏并不一致。
 */
constexpr char kDistributionVersion[] = "1.0.0";

// ------------------------------------------------------------------ JNI 工具

/** 取 Java 字符串内容；null 视为空串。 */
string ToStd(JNIEnv* env, jstring value) {
  if (value == nullptr) {
    return {};
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) {
    env->ExceptionClear();
    return {};
  }
  string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

/** 空串的全局引用，由 [InitJavaIds] 建立；候选注释、preedit 大量为空。 */
jstring g_empty_string = nullptr;

/**
 * 构造 Java String。
 *
 * 不能用 `NewStringUTF`：它按 JNI **modified UTF-8** 解码，而 librime 交出的是标准
 * UTF-8。基本平面两者一致；补充平面（扩展 B 起，如 𭎂）在标准 UTF-8 里是 4 字节，
 * 在 modified UTF-8 里是代理对的 6 字节，`NewStringUTF` 会把它解坏 —— 词库里的
 * 生僻字候选因此在候选栏里显示成乱码或直接丢字。这里按标准 UTF-8 自己解成 UTF-16
 * 再 `NewString`，非法字节输出 U+FFFD 并只前进一步，避免吞掉后续内容。
 *
 * C 侧的 char* 可能是 null（librime 大量使用 null 表示空），而 Kotlin 侧这些字段
 * 是非空的，直接传 null 会在构造函数里触发 `Intrinsics.checkNotNullParameter` 抛异常，
 * 故此处统一把 null 转成空串。
 */
jstring MakeString(JNIEnv* env, const char* value) {
  if (value == nullptr) {
    value = "";
  }
  if (*value == 0) {
    if (g_empty_string != nullptr) {
      return reinterpret_cast<jstring>(env->NewLocalRef(g_empty_string));
    }
    return env->NewStringUTF("");
  }
  // 基本平面（汉字、拼音、标点）在标准 UTF-8 与 modified UTF-8 的字节表示相同，
  // 直接交给 NewStringUTF 走 native 解码；只有 4 字节序列（扩展 B 起）、非法首字节
  // 或标准 UTF-8 不会出现的 0xC0/0xC1 才走下面的手写解码。
  bool needs_decoder = false;
  const unsigned char* probe = reinterpret_cast<const unsigned char*>(value);
  while (*probe != 0) {
    const unsigned char byte = *probe;
    if (byte < 0x80) {
      probe += 1;
    } else if (byte <= 0xC1) {
      // 0x80–0xBF 是非法首字节，0xC0/0xC1 是 overlong 两字节首字节。
      needs_decoder = true;
      break;
    } else if ((byte & 0xE0) == 0xC0) {
      if (probe[1] == 0) {
        needs_decoder = true;
        break;
      }
      probe += 2;
    } else if ((byte & 0xF0) == 0xE0) {
      if (probe[1] == 0 || probe[2] == 0) {
        needs_decoder = true;
        break;
      }
      probe += 3;
    } else if ((byte & 0xF8) == 0xF0) {
      // 4 字节序列：补充平面，modified UTF-8 与标准 UTF-8 不同，必须手写解码。
      needs_decoder = true;
      break;
    } else {
      needs_decoder = true;
      break;
    }
  }
  if (!needs_decoder) {
    return env->NewStringUTF(value);
  }

  std::vector<jchar> units;
  units.reserve(std::strlen(value));
  const unsigned char* cursor = reinterpret_cast<const unsigned char*>(value);
  while (*cursor != 0) {
    const unsigned char lead = *cursor;
    size_t length = 1;
    uint32_t code_point = 0;
    if (lead < 0x80) {
      code_point = lead;
    } else if ((lead & 0xE0) == 0xC0) {
      length = 2;
      code_point = lead & 0x1F;
    } else if ((lead & 0xF0) == 0xE0) {
      length = 3;
      code_point = lead & 0x0F;
    } else if ((lead & 0xF8) == 0xF0) {
      length = 4;
      code_point = lead & 0x07;
    } else {
      code_point = 0xFFFD;
    }
    bool valid = length > 1;
    for (size_t i = 1; valid && i < length; ++i) {
      const unsigned char continuation = cursor[i];
      if ((continuation & 0xC0) != 0x80) {
        valid = false;
        break;
      }
      code_point = (code_point << 6) | (continuation & 0x3F);
    }
    if (!valid && length > 1) {
      code_point = 0xFFFD;
      length = 1;
    }
    if (code_point <= 0xFFFF) {
      units.push_back(static_cast<jchar>(code_point));
    } else if (code_point <= 0x10FFFF) {
      const uint32_t offset = code_point - 0x10000;
      units.push_back(static_cast<jchar>(0xD800 + (offset >> 10)));
      units.push_back(static_cast<jchar>(0xDC00 + (offset & 0x3FF)));
    } else {
      units.push_back(static_cast<jchar>(0xFFFD));
    }
    cursor += length;
  }
  if (units.empty()) {
    if (g_empty_string != nullptr) {
      return reinterpret_cast<jstring>(env->NewLocalRef(g_empty_string));
    }
    return env->NewStringUTF("");
  }
  return env->NewString(units.data(), static_cast<jint>(units.size()));
}

// ------------------------------------------------------------------ 数据类构造
//
// 数据类的字段全部是 Kotlin `val`，因此不能用 SetXxxField 逐字段写入，而必须走
// 构造函数。签名必须与 EngineTypes.kt 的声明顺序严格一致。

constexpr char kCandidateCls[] = "com/qingyu/inputmethod/core/Candidate";
constexpr char kSchemaCls[] = "com/qingyu/inputmethod/core/Schema";
constexpr char kCommitCls[] = "com/qingyu/inputmethod/core/Commit";
constexpr char kCompositionCls[] = "com/qingyu/inputmethod/core/Composition";
constexpr char kCandidateMenuCls[] = "com/qingyu/inputmethod/core/CandidateMenu";
constexpr char kStatusCls[] = "com/qingyu/inputmethod/core/Status";
constexpr char kInputContextCls[] = "com/qingyu/inputmethod/core/InputContext";

// data class Candidate(comment: String, text: String)
constexpr char kCandidateCtor[] = "(Ljava/lang/String;Ljava/lang/String;)V";
// data class Schema(id: String, name: String)
constexpr char kSchemaCtor[] = "(Ljava/lang/String;Ljava/lang/String;)V";
// data class Commit(commitText: String)
constexpr char kCommitCtor[] = "(Ljava/lang/String;)V";
// data class Composition(length, cursorPos, selStart, selEnd, preedit: String?)
constexpr char kCompositionCtor[] = "(IIIILjava/lang/String;)V";
// data class CandidateMenu(pageSize, pageNo, isLastPage, highlightedCandidateIndex,
//                          numCandidates, candidates: Array<Candidate>)
constexpr char kCandidateMenuCtor[] =
    "(IIZII[Lcom/qingyu/inputmethod/core/Candidate;)V";
// data class Status(schemaId, schemaName, isDisable, isComposing, isAsciiMode,
//                   isFullShape, isSimplified, isTraditional, isAsciiPunch)
constexpr char kStatusCtor[] = "(Ljava/lang/String;Ljava/lang/String;ZZZZZZZ)V";
// data class InputContext(composition: Composition?, menu: CandidateMenu?,
//                         commitTextPreview: String?, selectLabels: Array<String>)
constexpr char kInputContextCtor[] =
    "(Lcom/qingyu/inputmethod/core/Composition;"
    "Lcom/qingyu/inputmethod/core/CandidateMenu;"
    "Ljava/lang/String;[Ljava/lang/String;)V";

/**
 * 进程内不变的类与方法句柄。
 *
 * 此前每条候选都 `FindClass` + `GetMethodID` 一次：`menu.page_size = 100` 时，
 * 每个按键光这一步就是 200 次类查找（FindClass 要走 ClassLoader 的锁与哈希），
 * 是 JNI 侧最大的一笔固定开销。类名与构造函数签名不随运行状态变化，首次使用时
 * 转成全局引用缓存即可；失败只尝试一次，避免坏了以后每键重试。
 */
struct JavaIds {
  bool ready = false;
  jclass candidate_cls = nullptr;
  jmethodID candidate_ctor = nullptr;
  jfieldID candidate_original = nullptr;
  jclass schema_cls = nullptr;
  jmethodID schema_ctor = nullptr;
  jclass commit_cls = nullptr;
  jmethodID commit_ctor = nullptr;
  jclass composition_cls = nullptr;
  jmethodID composition_ctor = nullptr;
  jclass candidate_menu_cls = nullptr;
  jmethodID candidate_menu_ctor = nullptr;
  jclass status_cls = nullptr;
  jmethodID status_ctor = nullptr;
  jclass input_context_cls = nullptr;
  jmethodID input_context_ctor = nullptr;
  jclass string_cls = nullptr;
};

JavaIds g_java_ids;
std::once_flag g_java_ids_once;

jclass CacheClass(JNIEnv* env, const char* name) {
  jclass local = env->FindClass(name);
  if (local == nullptr) {
    env->ExceptionClear();
    return nullptr;
  }
  jclass global = reinterpret_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  return global;
}

jmethodID CacheCtor(JNIEnv* env, jclass cls, const char* signature) {
  if (cls == nullptr) {
    return nullptr;
  }
  jmethodID ctor = env->GetMethodID(cls, "<init>", signature);
  if (ctor == nullptr) {
    env->ExceptionClear();
  }
  return ctor;
}

void InitJavaIds(JNIEnv* env) {
  g_java_ids.candidate_cls = CacheClass(env, kCandidateCls);
  g_java_ids.candidate_ctor = CacheCtor(env, g_java_ids.candidate_cls, kCandidateCtor);
  if (g_java_ids.candidate_cls)
    g_java_ids.candidate_original = env->GetFieldID(g_java_ids.candidate_cls, "isOriginal", "Z");
  g_java_ids.schema_cls = CacheClass(env, kSchemaCls);
  g_java_ids.schema_ctor = CacheCtor(env, g_java_ids.schema_cls, kSchemaCtor);
  g_java_ids.commit_cls = CacheClass(env, kCommitCls);
  g_java_ids.commit_ctor = CacheCtor(env, g_java_ids.commit_cls, kCommitCtor);
  g_java_ids.composition_cls = CacheClass(env, kCompositionCls);
  g_java_ids.composition_ctor = CacheCtor(env, g_java_ids.composition_cls, kCompositionCtor);
  g_java_ids.candidate_menu_cls = CacheClass(env, kCandidateMenuCls);
  g_java_ids.candidate_menu_ctor =
      CacheCtor(env, g_java_ids.candidate_menu_cls, kCandidateMenuCtor);
  g_java_ids.status_cls = CacheClass(env, kStatusCls);
  g_java_ids.status_ctor = CacheCtor(env, g_java_ids.status_cls, kStatusCtor);
  g_java_ids.input_context_cls = CacheClass(env, kInputContextCls);
  g_java_ids.input_context_ctor =
      CacheCtor(env, g_java_ids.input_context_cls, kInputContextCtor);
  g_java_ids.string_cls = CacheClass(env, "java/lang/String");
  g_empty_string = reinterpret_cast<jstring>(env->NewGlobalRef(env->NewStringUTF("")));
  g_java_ids.ready = g_java_ids.candidate_cls != nullptr &&
                     g_java_ids.candidate_ctor != nullptr &&
                     g_java_ids.candidate_original != nullptr &&
                     g_java_ids.commit_cls != nullptr &&
                     g_java_ids.commit_ctor != nullptr &&
                     g_java_ids.composition_cls != nullptr &&
                     g_java_ids.composition_ctor != nullptr &&
                     g_java_ids.candidate_menu_cls != nullptr &&
                     g_java_ids.candidate_menu_ctor != nullptr &&
                     g_java_ids.status_cls != nullptr &&
                     g_java_ids.status_ctor != nullptr &&
                     g_java_ids.input_context_cls != nullptr &&
                     g_java_ids.input_context_ctor != nullptr &&
                     g_java_ids.string_cls != nullptr;
}

/** 首次调用初始化；之后只读 `ready`。调用方在返回 false 时按旧语义降级。 */
bool EnsureJavaIds(JNIEnv* env) {
  std::call_once(g_java_ids_once, [env]() { InitJavaIds(env); });
  return g_java_ids.ready;
}

jobject NewCandidate(JNIEnv* env, const RimeCandidate& src) {
  jstring comment = MakeString(env, src.comment);
  jstring text = MakeString(env, src.text);
  jobject obj = env->NewObject(g_java_ids.candidate_cls, g_java_ids.candidate_ctor,
                               comment, text);
  env->DeleteLocalRef(comment);
  env->DeleteLocalRef(text);
  if (obj == nullptr) {
    env->ExceptionClear();
  }
  return obj;
}

jobject NewComposition(JNIEnv* env, const RimeComposition& src) {
  jstring preedit = MakeString(env, src.preedit);
  jobject obj = env->NewObject(g_java_ids.composition_cls,
                               g_java_ids.composition_ctor,
                               static_cast<jint>(src.length),
                               static_cast<jint>(src.cursor_pos),
                               static_cast<jint>(src.sel_start),
                               static_cast<jint>(src.sel_end), preedit);
  env->DeleteLocalRef(preedit);
  if (obj == nullptr) {
    env->ExceptionClear();
  }
  return obj;
}

jobject NewCandidateMenu(JNIEnv* env, const RimeMenu& src) {
  const int count = src.num_candidates > 0 ? src.num_candidates : 0;
  jobjectArray candidates =
      env->NewObjectArray(count, g_java_ids.candidate_cls, nullptr);
  if (candidates == nullptr) {
    env->ExceptionClear();
    return nullptr;
  }
  for (int i = 0; i < count; ++i) {
    jobject item = NewCandidate(env, src.candidates[i]);
    if (item && IsOriginalCandidate(g_session, static_cast<size_t>(src.page_no * src.page_size + i)))
      env->SetBooleanField(item, g_java_ids.candidate_original, JNI_TRUE);
    if (item == nullptr) {
      // Kotlin 的 Array<Candidate> 不接受 null 元素；宁可整页降级，也不要把
      // 带洞的数组交给上层（后续 candidate.text 会直接 NPE）。
      env->DeleteLocalRef(candidates);
      return nullptr;
    }
    env->SetObjectArrayElement(candidates, i, item);
    env->DeleteLocalRef(item);
  }

  jobject obj = env->NewObject(
      g_java_ids.candidate_menu_cls, g_java_ids.candidate_menu_ctor,
      static_cast<jint>(src.page_size), static_cast<jint>(src.page_no),
      static_cast<jboolean>(src.is_last_page != 0),
      static_cast<jint>(src.highlighted_candidate_index),
      static_cast<jint>(src.num_candidates), candidates);
  env->DeleteLocalRef(candidates);
  if (obj == nullptr) {
    env->ExceptionClear();
  }
  return obj;
}

jobject NewStatus(JNIEnv* env, const RimeStatus& src) {
  jstring schema_id = MakeString(env, src.schema_id);
  jstring schema_name = MakeString(env, src.schema_name);
  jobject obj = env->NewObject(
      g_java_ids.status_cls, g_java_ids.status_ctor, schema_id, schema_name,
      static_cast<jboolean>(src.is_disabled != 0),
      static_cast<jboolean>(src.is_composing != 0),
      static_cast<jboolean>(src.is_ascii_mode != 0),
      static_cast<jboolean>(src.is_full_shape != 0),
      static_cast<jboolean>(src.is_simplified != 0),
      static_cast<jboolean>(src.is_traditional != 0),
      static_cast<jboolean>(src.is_ascii_punct != 0));
  env->DeleteLocalRef(schema_id);
  env->DeleteLocalRef(schema_name);
  if (obj == nullptr) {
    env->ExceptionClear();
  }
  return obj;
}

jobject NewInputContext(JNIEnv* env, const RimeContext& src) {
  if (!EnsureJavaIds(env)) {
    return nullptr;
  }
  // select_labels 是 null 结尾的 char* 数组
  int label_count = 0;
  if (src.select_labels != nullptr) {
    while (src.select_labels[label_count] != nullptr) {
      ++label_count;
    }
  }
  jobjectArray labels =
      env->NewObjectArray(label_count, g_java_ids.string_cls, nullptr);
  if (labels == nullptr) {
    env->ExceptionClear();
    return nullptr;
  }
  for (int i = 0; i < label_count; ++i) {
    jstring label = MakeString(env, src.select_labels[i]);
    env->SetObjectArrayElement(labels, i, label);
    env->DeleteLocalRef(label);
  }

  jobject composition = NewComposition(env, src.composition);
  jobject menu = NewCandidateMenu(env, src.menu);
  jstring preview = MakeString(env, src.commit_text_preview);

  jobject obj = env->NewObject(g_java_ids.input_context_cls,
                               g_java_ids.input_context_ctor,
                               composition, menu, preview, labels);
  env->DeleteLocalRef(preview);
  env->DeleteLocalRef(labels);
  if (composition != nullptr) env->DeleteLocalRef(composition);
  if (menu != nullptr) env->DeleteLocalRef(menu);
  if (obj == nullptr) {
    env->ExceptionClear();
  }
  return obj;
}

// ------------------------------------------------------------------ 输入串处理

/**
 * 把 UTF-8 串按**字符**切分，得到每个字符的起始字节偏移。
 *
 * 上层传入的插入位置来自 Kotlin String 下标（即字符下标，UTF-16 code unit），
 * 而 librime 的 preedit 是 UTF-8。拼音输入串通常全为 ASCII（两者等价），但为了
 * 在含多字节字符时不静默错位，这里显式按 UTF-8 边界换算。
 */
vector<size_t> Utf8CharOffsets(const string& text) {
  vector<size_t> offsets;
  for (size_t i = 0; i < text.size();) {
    offsets.push_back(i);
    const unsigned char byte = static_cast<unsigned char>(text[i]);
    if (byte < 0x80) {
      i += 1;
    } else if ((byte & 0xE0) == 0xC0) {
      i += 2;
    } else if ((byte & 0xF0) == 0xE0) {
      i += 3;
    } else if ((byte & 0xF8) == 0xF0) {
      i += 4;
    } else {
      i += 1;  // 非法字节，逐字节前进，避免死循环
    }
  }
  offsets.push_back(text.size());  // 末尾哨兵，便于取子串
  return offsets;
}

/** 取当前输入串（未上屏的编辑内容）。 */
bool CurrentInput(string* out) {
  RimeContext context = {0};
  RIME_STRUCT_INIT(RimeContext, context);
  if (!RimeGetContext(g_session, &context)) {
    return false;
  }
  *out = context.composition.preedit != nullptr ? context.composition.preedit : "";
  RimeFreeContext(&context);
  return true;
}

}  // namespace

// ==================================================================== JNI 入口
//
// Kotlin 侧全部声明为 `private external fun`（对象方法），故第二个参数是 jobject。

extern "C" {

/**
 * 启动引擎。
 *
 * 幂等：已启动则直接返回。
 *
 * 关于 `recheckData`：上游语义是"让 deployer 重新校验词库数据"。本工程只分发
 * **编译产物**（`assets/rime/build/` 下的 `.bin` 与配置），不含 `*.dict.yaml` 源，
 * 设备端无法重建数据，因此此处不做 deploy；该参数预留给将来接入 schema 源后使用。
 */
JNIEXPORT jboolean JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeStart(
    JNIEnv* env, jobject /*thiz*/, jobject /*context*/, jstring shared_data_dir,
    jstring user_data_dir, jstring log_dir_arg, jboolean recheck_data) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session != 0) {
    return JNI_TRUE;
  }

  const string shared = ToStd(env, shared_data_dir);
  const string user = ToStd(env, user_data_dir);
  const string log_dir = ToStd(env, log_dir_arg);
  (void)recheck_data;

  // 这三个 string 必须活到 RimeInitialize 返回为止 —— traits 只持有 char*，
  // 不能指向临时对象（如 `(shared + "/build")`）的缓冲区。
  const string shared_build = shared + "/build";
  const string user_build = user + "/build";

  QY_LOGD("nativeStart: shared=%s user=%s", shared.c_str(), user.c_str());
  // 引擎只从 <dir>/build 读配置与编译产物，故这里显式确认数据是否就位 ——
  // 资产由后台线程拷贝，若此刻尚未完成，后面会"静默"地什么都查不到。
  const string schema_probe = shared_build + "/pinyin.schema.yaml";
  const string table_probe = shared_build + "/pinyin.table.bin";
  QY_LOGD("  数据就位? schema.yaml=%d table.bin=%d",
          access(schema_probe.c_str(), F_OK) == 0,
          access(table_probe.c_str(), F_OK) == 0);

  RimeTraits traits = {0};
  RIME_STRUCT_INIT(RimeTraits, traits);
  traits.shared_data_dir = shared.c_str();
  traits.user_data_dir = user.c_str();
  traits.distribution_name = "Qingyu IME";
  traits.distribution_code_name = "qingyu";
  traits.distribution_version = kDistributionVersion;
  // app_name 需带 "rime." 前缀，librime 据此清理旧日志文件
  traits.app_name = "rime.qingyu";
  // 显式指定 build 目录：默认值即为 ${data_dir}/build，此处写出来是为了让
  // 「配置与编译产物必须同处 build/」这一约定在代码里可见（见接口契约文档）。
  traits.prebuilt_data_dir = shared_build.c_str();
  traits.staging_dir = user_build.c_str();
  // 引擎日志。默认**不启用** —— CMake 里 ENABLE_LOGGING=OFF，原因见那里的注释
  // （glog 二次初始化会 abort，而 `Kernel.resetIme()` 每进程都会走一次 stop → start）。
  // 重新打开时这两行才生效；注意**不能写 stderr** —— Android 应用的原生 stderr 会被
  // zygote 丢弃，日志会凭空消失；且目录要在应用内部存储，外部存储上的文件 adb 取不出来。
  if (!log_dir.empty()) {
    traits.log_dir = log_dir.c_str();
    traits.min_log_level = 0;  // INFO
  }

  QY_LOGD("→ RimeSetup");
  RimeSetup(&traits);
  QY_LOGD("→ RimeInitialize");
  RimeInitialize(&traits);
  RegisterNativeScriptTranslator();
  QY_LOGD("→ RimeCreateSession");
  const RimeSessionId session = RimeCreateSession();
  QY_LOGD("← RimeCreateSession 返回 %llu", (unsigned long long)session);
  if (session == 0) {
    QY_LOGE("RimeCreateSession 返回 0 —— 引擎不可用（Service 处于 disabled 状态）");
    RimeFinalize();
    return JNI_FALSE;
  }
  g_session = session;
  // 在 Java 调用栈上初始化一次 JNI 句柄缓存，避免后台线程首次 FindClass 时
  // 落到与 App 不同的 ClassLoader 上下文。
  if (!EnsureJavaIds(env)) {
    QY_LOGW("JNI 数据类句柄初始化失败 —— 检查 EngineTypes.kt 与 jni.cc 的类名/签名");
  }
  char current_schema[256] = {0};
  if (RimeGetCurrentSchema(g_session, current_schema, sizeof(current_schema))) {
    QY_LOGD("会话已建立，当前方案 = %s", current_schema);
  } else {
    QY_LOGW("会话已建立，但读不到当前方案");
  }
  return JNI_TRUE;
}

/**
 * 使用 librime 的公开 deployer API 编译一份独立 pack。
 *
 * 调用方保证当前没有活动会话；输出只写入 staging_dir。失败时 active pack 指针不变，
 * 因而不会破坏上一份可用词库。
 */
JNIEXPORT jboolean JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeBuildWorkspace(
    JNIEnv* env, jobject /*thiz*/, jstring shared_data_dir,
    jstring user_data_dir, jstring staging_dir_arg, jstring log_dir_arg) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session != 0) {
    QY_LOGE("编译 pack 时仍有活动会话");
    return JNI_FALSE;
  }

  const string shared = ToStd(env, shared_data_dir);
  const string user = ToStd(env, user_data_dir);
  const string staging = ToStd(env, staging_dir_arg);
  const string log_dir = ToStd(env, log_dir_arg);
  const string prebuilt = shared + "/build";

  RimeTraits traits = {0};
  RIME_STRUCT_INIT(RimeTraits, traits);
  traits.shared_data_dir = shared.c_str();
  traits.user_data_dir = user.c_str();
  traits.prebuilt_data_dir = prebuilt.c_str();
  traits.staging_dir = staging.c_str();
  traits.distribution_name = "Qingyu IME Pack Builder";
  traits.distribution_code_name = "qingyu-pack";
  traits.distribution_version = kDistributionVersion;
  traits.app_name = "rime.qingyu.pack";
  if (!log_dir.empty()) {
    traits.log_dir = log_dir.c_str();
    traits.min_log_level = 0;
  }

  QY_LOGD("编译 pack: shared=%s staging=%s", shared.c_str(), staging.c_str());
  RimeDeployerInitialize(&traits);
  const bool ok = RimeDeployWorkspace();
  RimeFinalize();
  if (!ok) QY_LOGE("RimeDeployWorkspace 失败");
  return ok ? JNI_TRUE : JNI_FALSE;
}

/** 关闭引擎并释放资源。 */
JNIEXPORT void JNICALL Java_com_qingyu_inputmethod_core_RimeSession_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session == 0) {
    return;
  }
  CloseAssociationSession();
  CloseStrokeWildcardSession();
  RimeDestroySession(g_session);
  g_session = 0;
  RimeFinalize();
}

/** 送入一个按键，返回引擎是否消费。 */
JNIEXPORT jboolean JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeProcessKey(
    JNIEnv* /*env*/, jobject /*thiz*/, jint keycode, jint modifiers) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session == 0) {
    QY_LOGW("processKey 被忽略：引擎未启动");
    return JNI_FALSE;
  }
  const Bool consumed = RimeProcessKey(g_session, keycode, modifiers);
  // 键未被消费是"引擎没在处理输入"最直接的信号，故把结果一并记下
  return consumed ? JNI_TRUE : JNI_FALSE;
}

/**
 * 用 [text] 替换输入串中从 [caret_position] 起、长度为 [length] 的一段。
 *
 * 上游没有"区间替换"API，这里用「读出当前输入串 → 拼出新串 → RimeSetInput 整体设回」
 * 实现。旧预编译库的 `replaceRimeKey` 是私有补丁，本实现与之的差异需真机对照验证。
 */
JNIEXPORT jboolean JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeReplaceInput(
    JNIEnv* env, jobject /*thiz*/, jint caret_position, jint length,
    jstring text) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session == 0) {
    return JNI_FALSE;
  }

  string current;
  if (!CurrentInput(&current)) {
    return JNI_FALSE;
  }
  const string replacement = ToStd(env, text);

  // 字符下标 → 字节偏移
  const vector<size_t> offsets = Utf8CharOffsets(current);
  const size_t char_count = offsets.size() - 1;
  if (caret_position < 0 || static_cast<size_t>(caret_position) > char_count) {
    return JNI_FALSE;
  }
  const size_t begin = offsets[static_cast<size_t>(caret_position)];
  const size_t end_char =
      std::min(static_cast<size_t>(caret_position) + static_cast<size_t>(length),
               char_count);
  const size_t end = offsets[end_char];

  const string updated = current.substr(0, begin) + replacement + current.substr(end);
  return RimeSetInput(g_session, updated.c_str()) ? JNI_TRUE : JNI_FALSE;
}

/** 清空输入串。 */
JNIEXPORT void JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeClearInput(JNIEnv* /*env*/,
                                                              jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session == 0) {
    return;
  }
  RimeClearComposition(g_session);
}

/** 取上屏结果；无内容时返回 null。 */
JNIEXPORT jobject JNICALL Java_com_qingyu_inputmethod_core_RimeSession_nativeCommit(
    JNIEnv* env, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session == 0) {
    return nullptr;
  }
  RimeCommit commit = {0};
  RIME_STRUCT_INIT(RimeCommit, commit);
  if (!RimeGetCommit(g_session, &commit)) {
    return nullptr;
  }
  jobject result = nullptr;
  if (EnsureJavaIds(env)) {
    jstring text = MakeString(env, commit.text);
    result = env->NewObject(g_java_ids.commit_cls, g_java_ids.commit_ctor, text);
    env->DeleteLocalRef(text);
    if (result == nullptr) {
      env->ExceptionClear();
    }
  }
  RimeFreeCommit(&commit);
  return result;
}

/** 取当前输入上下文。 */
JNIEXPORT jobject JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeContext(JNIEnv* env,
                                                           jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session == 0) {
    return nullptr;
  }
  RimeContext context = {0};
  RIME_STRUCT_INIT(RimeContext, context);
  if (!RimeGetContext(g_session, &context)) {
    QY_LOGW("RimeGetContext 返回 false（会话失效或引擎未就绪）");
    return nullptr;
  }
  jobject result = NewInputContext(env, context);
  RimeFreeContext(&context);
  if (result == nullptr) {
    QY_LOGE("构造 InputContext 失败 —— 检查 EngineTypes.kt 的构造函数签名是否与 jni.cc 一致");
  }
  return result;
}

/** 取引擎状态。 */
JNIEXPORT jobject JNICALL Java_com_qingyu_inputmethod_core_RimeSession_nativeStatus(
    JNIEnv* env, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session == 0) {
    return nullptr;
  }
  RimeStatus status = {0};
  RIME_STRUCT_INIT(RimeStatus, status);
  if (!RimeGetStatus(g_session, &status)) {
    QY_LOGW("RimeGetStatus 返回 false");
    return nullptr;
  }
  if (!EnsureJavaIds(env)) {
    RimeFreeStatus(&status);
    return nullptr;
  }
  QY_LOGD("status: schema=%s composing=%d ascii_mode=%d disabled=%d",
          status.schema_id != nullptr ? status.schema_id : "?",
          status.is_composing != 0, status.is_ascii_mode != 0,
          status.is_disabled != 0);
  char mode[4] = {};
  RimeGetProperty(g_session, "qingyu_script_mode", mode, sizeof(mode));
  status.is_traditional = mode[0] == '1';
  status.is_simplified = mode[0] != '1' && mode[0] != '2';
  jobject result = NewStatus(env, status);
  RimeFreeStatus(&status);
  return result;
}

/** 设置引擎选项（如 traditionalization / ascii_mode）。 */
JNIEXPORT jboolean JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeSetScriptMode(JNIEnv*, jobject, jint mode) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return SetNativeScriptMode(g_session, mode) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeSetOption(JNIEnv* env,
                                                             jobject /*thiz*/,
                                                             jstring option,
                                                             jboolean value) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session == 0) {
    return;
  }
  const string name = ToStd(env, option);
  RimeSetOption(g_session, name.c_str(), value ? True : False);
}

/** 当前方案 id。 */
JNIEXPORT jstring JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeCurrentSchema(JNIEnv* env,
                                                                 jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session == 0) {
    return MakeString(env, "");
  }
  char buffer[256] = {0};
  if (!RimeGetCurrentSchema(g_session, buffer, sizeof(buffer))) {
    return MakeString(env, "");
  }
  return MakeString(env, buffer);
}

/** 切换输入方案。 */
JNIEXPORT jboolean JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeSelectSchema(JNIEnv* env,
                                                                jobject /*thiz*/,
                                                                jstring schema_id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session == 0) {
    QY_LOGW("selectSchema 被忽略：引擎未启动");
    return JNI_FALSE;
  }
  const string id = ToStd(env, schema_id);
  const Bool ok = RimeSelectSchema(g_session, id.c_str());
  QY_LOGD("selectSchema(\"%s\") -> %d", id.c_str(), ok != 0);
  if (ok) {
    // 注意：上游的 RimeSelectSchema 只在「会话无效 / 参数为空」时返回 false，
    // **配置装载失败它照样返回 true**，所以调用方不能拿它当"方案已就绪"的证据。
    // 这里把切换后的实际方案记下来，便于把两者区分开。
    char current[256] = {0};
    if (RimeGetCurrentSchema(g_session, current, sizeof(current))) {
      QY_LOGD("  切换后 currentSchema=%s", current);
    }
  }
  return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * 选中第 [index] 个候选。
 *
 * index 为**全局**下标（不是页内下标），故走 `RimeSelectCandidate` 而非
 * `RimeSelectCandidateOnCurrentPage`。
 */
JNIEXPORT jboolean JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeSelectCandidate(
    JNIEnv* /*env*/, jobject /*thiz*/, jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session == 0 || index < 0) {
    return JNI_FALSE;
  }
  return RimeSelectCandidate(g_session, static_cast<size_t>(index)) ? JNI_TRUE
                                                                   : JNI_FALSE;
}

/** 按键名取键码（如 `Page_Down`、`BackSpace`）。 */
JNIEXPORT jint JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeKeycodeOf(JNIEnv* env,
                                                             jobject /*thiz*/,
                                                             jstring name) {
  const string key_name = ToStd(env, name);
  const int keycode = RimeGetKeycodeByName(key_name.c_str());
  return static_cast<jint>(keycode);
}

/** One bounded probe per lock acquisition so typing can interleave with cold lookup. */
JNIEXPORT jobjectArray JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeAssociationProbe(
    JNIEnv* env, jobject /*thiz*/, jstring input, jstring prefix, jint max_pages, jint mode) {
  std::lock_guard<std::mutex> lock(g_mutex);
  const auto words = g_session == 0 ? vector<string>{} :
      QueryAssociation(ToStd(env, input), ToStd(env, prefix), max_pages, mode);
  if (!EnsureJavaIds(env)) return nullptr;
  jobjectArray result = env->NewObjectArray(words.size(), g_java_ids.string_cls,
                                            nullptr);
  if (!result) return nullptr;
  for (size_t i = 0; i < words.size(); ++i) {
    jstring word = MakeString(env, words[i].c_str());
    env->SetObjectArrayElement(result, i, word);
    env->DeleteLocalRef(word);
    if (env->ExceptionCheck()) return nullptr;
  }
  return result;
}

/** 五笔画通配：`*` 代表任意一笔，展开后合并结果。 */
JNIEXPORT jobjectArray JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeStrokeWildcard(
    JNIEnv* env, jobject /*thiz*/, jstring pattern, jint max_candidates) {
  std::lock_guard<std::mutex> lock(g_mutex);
  const auto words = g_session == 0 ? vector<string>{} :
      QueryStrokeWildcard(ToStd(env, pattern), max_candidates);
  if (!EnsureJavaIds(env)) return nullptr;
  jobjectArray result = env->NewObjectArray(words.size(), g_java_ids.string_cls,
                                            nullptr);
  if (!result) return nullptr;
  for (size_t i = 0; i < words.size(); ++i) {
    jstring word = MakeString(env, words[i].c_str());
    env->SetObjectArrayElement(result, i, word);
    env->DeleteLocalRef(word);
    if (env->ExceptionCheck()) return nullptr;
  }
  return result;
}

/** UTF-8 payload avoids JNI modified-UTF-8 ambiguity for supplementary Han characters. */
JNIEXPORT jobjectArray JNICALL
Java_com_qingyu_inputmethod_core_RimeSession_nativeStrokeCodes(
    JNIEnv* env, jobject, jstring path, jbyteArray characters) {
  if (!characters) return nullptr;
  const jsize size = env->GetArrayLength(characters);
  if (size < 1 || size > 1024) return nullptr;
  string input(size, '\0');
  env->GetByteArrayRegion(characters, 0, size, reinterpret_cast<jbyte*>(input.data()));
  if (env->ExceptionCheck()) return nullptr;
  const auto codes = LookupStrokeCodes(ToStd(env, path), input);
  if (!EnsureJavaIds(env)) return nullptr;
  jobjectArray result = env->NewObjectArray(codes.size(), g_java_ids.string_cls,
                                            nullptr);
  if (!result) return nullptr;
  for (size_t i = 0; i < codes.size(); ++i) {
    jstring code = MakeString(env, codes[i].c_str());
    env->SetObjectArrayElement(result, i, code);
    env->DeleteLocalRef(code);
    if (env->ExceptionCheck()) return nullptr;
  }
  return result;
}

}  // extern "C"
