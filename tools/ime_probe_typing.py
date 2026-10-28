"""在 IME Lab 里输入拼音并抓候选栏截图。

两个已知坑，这里都规避：
1. `adb shell am force-stop` 会连带停掉输入法进程，系统随即把默认输入法回退到
   谷歌键盘，之后拍到的图就不是目标输入法了。因此每轮开始先重新 `ime set`。
2. 键盘每次重新加载都会重排键位，固定坐标会打到相邻键。因此先截图量出真实
   键块再点击。
"""
import os
import subprocess
import sys
import tempfile
import time

from PIL import Image

ADB = os.environ.get("ADB", "adb")
QY_IME = "com.qingyu.inputmethod.offline.debug/com.qingyu.imemodule.service.ImeService"
SOGOU_IME = "com.sohu.inputmethod.sogou.meizu/.SogouIME"
LAB = "com.qingyu.imelab/.MainActivity"
# Field A 的中心（IME Lab 首个输入框，bounds [20,128][1060,268]）
FIELD_A = (540, 198)
HIDE_BTN = (910, 791)

LAYOUT = {
    "q": (0, 0), "w": (0, 1), "e": (0, 2), "r": (0, 3), "t": (0, 4),
    "y": (0, 5), "u": (0, 6), "i": (0, 7), "o": (0, 8), "p": (0, 9),
    "a": (1, 0), "s": (1, 1), "d": (1, 2), "f": (1, 3), "g": (1, 4),
    "h": (1, 5), "j": (1, 6), "k": (1, 7), "l": (1, 8),
    "z": (2, 1), "x": (2, 2), "c": (2, 3), "v": (2, 4), "b": (2, 5),
    "n": (2, 6), "m": (2, 7),
}


def adb(*args, binary=False):
    r = subprocess.run([ADB, *args], capture_output=True)
    return r.stdout if binary else r.stdout.decode("utf-8", "ignore")


def use_ime(ime):
    adb("shell", "ime", "enable", ime)
    adb("shell", "ime", "set", ime)
    time.sleep(2)
    return adb("shell", "settings", "get", "secure", "default_input_method").strip()


def screencap(path):
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(path, "wb") as f:
        f.write(data)
    return Image.open(path).convert("RGB")


def key_rows(im):
    """量出键盘每行按键中心：返回 [(yTop, yBottom, [xCenter...]), ...]。"""
    w, h = im.size
    px = im.load()
    merged = []
    for y in range(h):
        runs, cur, st = [], None, 0
        for x in range(w):
            wt = all(v > 252 for v in px[x, y])
            if wt != cur:
                if cur:
                    runs.append((st, x - 1))
                cur, st = wt, x
        if cur:
            runs.append((st, w - 1))
        centers = [(a + b) // 2 for a, b in runs if b - a > 40]
        if len(centers) < 9:
            continue
        if merged and y - merged[-1][1] <= 12 and centers == merged[-1][2]:
            merged[-1][1] = y
        else:
            merged.append([y, y, centers])
    return [tuple(m) for m in merged]


def ime_shown():
    return "mInputShown=true" in adb("shell", "dumpsys", "input_method")


def open_keyboard(ime):
    """切换输入法并让 Field A 获得焦点、键盘确实弹出。

    注意顺序：先停宿主再停输入法，最后重设 `ime set` 并重启宿主。
    停掉输入法进程后系统会回退默认输入法，所以 `ime set` 必须放在最后。
    冷启动后服务要几秒才连上，这里轮询等待而不是死等固定时长。
    """
    adb("shell", "am", "force-stop", "com.qingyu.imelab")
    adb("shell", "am", "force-stop", ime.split("/")[0])
    time.sleep(2)
    adb("shell", "ime", "enable", ime)
    adb("shell", "ime", "set", ime)
    time.sleep(3)
    adb("shell", "am", "start", "-n", LAB)
    time.sleep(4)
    # 冷启动时轻语要建词库会话，窗口弹出可能慢到十几秒，逐轮点击并轮询
    for attempt in range(8):
        adb("shell", "input", "tap", str(FIELD_A[0]), str(FIELD_A[1]))
        for _ in range(10):
            if ime_shown():
                time.sleep(2)
                return True
            time.sleep(1)
        # 没弹出来就重新拉起宿主（服务可能刚被系统回收）
        adb("shell", "am", "start", "-n", LAB)
        time.sleep(3)
    return ime_shown()


def keyboard_visible(im):
    return any(len(c) >= 10 for _, _, c in key_rows(im))


def type_letters(im, letters):
    """按当前截图量出的键位逐字点击。"""
    rows = [r for r in key_rows(im) if len(r[2]) == 10]
    if len(rows) < 3:
        return False
    picks = {0: rows[0], 1: rows[1], 2: rows[2]}
    for ch in letters.lower():
        if ch not in LAYOUT:
            continue
        ri, ci = LAYOUT[ch]
        y0, y1, xs = picks[ri]
        if ci >= len(xs):
            return False
        adb("shell", "input", "tap", str(xs[ci]), str((y0 + y1) // 2))
        time.sleep(0.7)
    return True


def main():
    letters = sys.argv[1] if len(sys.argv) > 1 else "nihao"
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "qingyu-probe.png")
    target = sys.argv[3] if len(sys.argv) > 3 else "qy"
    reuse = "--reuse" in sys.argv
    ime = SOGOU_IME if target == "sogou" else QY_IME

    # --reuse：键盘已经弹着，只量键位并输入，不重启输入法
    if reuse and ime_shown():
        im = screencap(out)
        if keyboard_visible(im):
            if not type_letters(im, letters):
                print("NO_QWERTY_LAYOUT")
                return
            time.sleep(2)
            screencap(out)
            print("OK reused ime=", adb("shell", "settings", "get", "secure", "default_input_method").strip(),
                  "typed=", letters, "shot=", out)
            return

    if not open_keyboard(ime):
        print("KEYBOARD_NOT_SHOWN ime=", adb("shell", "settings", "get", "secure", "default_input_method").strip())
        return
    im = screencap(out)
    if not keyboard_visible(im):
        print("KEYBOARD_NOT_SHOWN ime=", adb("shell", "settings", "get", "secure", "default_input_method").strip())
        return

    if not type_letters(im, letters):
        print("NO_QWERTY_LAYOUT")
        return
    time.sleep(2)
    screencap(out)
    print("OK ime=", adb("shell", "settings", "get", "secure", "default_input_method").strip(),
          "typed=", letters, "shot=", out)


main()