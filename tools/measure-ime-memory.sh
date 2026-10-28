#!/usr/bin/env bash
# 采集输入法进程的内存占用，用于验证性能问题。
#
# 用法: bash tools/measure-ime-memory.sh [包名]
# 输出: 多次采样的 PSS/RSS 与 native 堆，以及拆解说明。

set -uo pipefail

PKG="${1:-com.qingyu.inputmethod.offline}"
ADB="/d/data/Android/Sdk/platform-tools/adb.exe"
EMU="/d/data/Android/Sdk/emulator/emulator.exe"
AVD="${AVD:-qingyu_test}"

"$ADB" wait-for-device
echo "设备就绪"
echo

# 1. 基础信息
echo "===== 设备 ====="
"$ADB" shell getprop ro.product.model
"$ADB" shell getprop ro.build.version.release
"$ADB" shell getprop ro.product.cpu.abi
echo

# 2. 包是否安装
echo "===== 安装状态 ====="
if "$ADB" shell pm list packages | grep -q "$PKG"; then
  echo "已安装: $PKG"
else
  echo "未安装: $PKG"
  echo "可用包名:"
  "$ADB" shell pm list packages | grep -i qingyu
  exit 1
fi
echo

# 3. 进程是否存在（未启动则先启动输入法设置页）
echo "===== 进程状态 ====="
PID=$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r')
if [ -z "$PID" ]; then
  echo "进程未运行，先打开设置页拉起"
  "$ADB" shell am start -n "$PKG/com.qingyu.imemodule.ui.activity.SettingsActivity" > /dev/null 2>&1
  sleep 4
  PID=$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r')
fi
if [ -z "$PID" ]; then
  echo "无法拉起进程，请先在系统设置中启用输入法"
  exit 1
fi
echo "PID=$PID"
echo

# 4. dumpsys meminfo 多次采样
echo "===== 内存占用（PSS 单位 KB）====="
for i in 1 2 3; do
  echo "--- 第 $i 次采样 ---"
  "$ADB" shell dumpsys meminfo "$PKG" 2>/dev/null \
    | grep -E "TOTAL PSS|TOTAL RSS|Native Heap|Dalvik Heap|Native Heap Size|TOTAL:" \
    | head -12
  [ $i -lt 3 ] && sleep 2
done
echo

# 5. smaps 汇总（PSS 视角）
echo "===== smaps_rollup ====="
"$ADB" shell cat "/proc/$PID/smaps_rollup" 2>/dev/null | head -12
echo

# 6. 磁盘占用
echo "===== 磁盘占用 ====="
for d in files cache databases; do
  SIZE=$("$ADB" shell du -sk "/data/data/$PKG/$d" 2>/dev/null | awk '{print $1}')
  if [ -n "$SIZE" ] && [ "$SIZE" != "0" ]; then
    printf "  %-12s %8.1f MB\n" "$d" "$(echo "$SIZE/1024" | bc -l)"
    "$ADB" shell du -sk "/data/data/$PKG/$d"/* 2>/dev/null | sort -rn | head -5 | while read -r line; do
      sz=$(echo "$line" | awk '{print $1}')
      nm=$(echo "$line" | awk '{print $2}')
      [ -n "$sz" ] && printf "      %-40s %8.1f MB\n" "$nm" "$(echo "$sz/1024" | bc -l)"
    done
  fi
done
echo

# 7. so 与模型占用
echo "===== native so 已加载 ====="
"$ADB" shell cat "/proc/$PID/maps" 2>/dev/null | grep -oE "/data/app/[^ ]*\.so" | sort -u | head -10