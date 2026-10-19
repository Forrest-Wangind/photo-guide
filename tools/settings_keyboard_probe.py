"""设置页软键盘行为回归（连真机/模拟器，非单元测试）。

为什么有这个脚本：`commonTest` 基建至今未接入，而"软键盘收没收"这类行为
单元测试根本覆盖不到（它取决于 Compose 焦点模型 + Android IME + ROM 行为），
只能靠 UI 层实测。手工点一遍很容易漏，所以固化成脚本。

覆盖路径（每条都断言 `mInputShown`）：
  1 点输入框            → 键盘弹出
  2 点空白处            → 键盘收起（说明文字区域不可点击，走父级 pointerInput）
  3 点端点卡片          → 键盘收起（clickable 兄弟节点不抢焦点，原来的病根）
  4 点另一个输入框      → 键盘仍弹出（确认收起逻辑没有把焦点弄丢）
  5 键盘弹起时          → 顶栏「完成」仍在视口内、且在键盘上方（固定顶栏回归）
  6 键盘开着点「完成」  → 键盘收起 + 回到拍照页

用法：
    python tools/settings_keyboard_probe.py          # 3 轮
    python tools/settings_keyboard_probe.py 5        # 指定轮数

前置：设备已连 adb、APK 已装、屏幕解锁。App 会自动被拉到前台。
不依赖相机，所以 AVD（模拟器）上就能跑 —— 相机链路才必须真机。

⚠️ 两个坑（写死坐标就会踩）：
  · 键盘弹出触发 adjustResize + 自动滚动，布局整体位移，
    必须每一步重新 dump 层级拿实时 bounds；
  · 键盘会遮住下方输入框，层级里看不到 —— 需要先收键盘 / 滚回上方再定位。

第 5 条是本脚本最有价值的一条：它锁的正是 2026-10-15 修掉的病 ——
此前顶栏跟内容一起滚动，键盘一弹就被推出屏幕上方，用户想离开设置页得先滑回顶部。
"""
import re
import subprocess
import sys
import time

ADB = "adb"
PKG = "com.photoguide"
ROUNDS = int(sys.argv[1]) if len(sys.argv) > 1 else 3

# 页面判据与"点空白"目标都用**固定顶栏**（2026-10-15 起）。
# 最初用"设置页里一句不可点击的说明文字"当锚点，但它会随内容滚出视口 ——
# 键盘一弹、内容区自动上滚，锚点消失，脚本就"找不到设置页"了。
# 顶栏（标题 + 「完成」）拆出来固定之后永远可见，才是稳定锚点。
HEADER_DONE = "完成"      # 顶栏按钮；拍照页没有它（拍照页只有 调试/设置）
HEADER_TITLE = "设置"      # 顶栏标题；非 clickable，点它 = 点空白收键盘
BLANK_ANCHOR = "端点即切换生效"  # 备用：说明文字可见时优先点它（离输入区更近）


def sh(args):
    return subprocess.run([ADB] + args, capture_output=True, text=True,
                          encoding="utf-8", errors="replace").stdout


def dump_xml():
    sh(["shell", "uiautomator", "dump", "/sdcard/d.xml"])
    return sh(["shell", "cat", "/sdcard/d.xml"])


NODE_RE = re.compile(r"<node ([^>]*?)/?>")
ATTR_RE = re.compile(r'(\w[\w-]*)="([^"]*)"')


def nodes():
    out = []
    for m in NODE_RE.finditer(dump_xml()):
        a = dict(ATTR_RE.findall(m.group(1)))
        mm = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", a.get("bounds", ""))
        if not mm:
            continue
        x1, y1, x2, y2 = map(int, mm.groups())
        a.update(cx=(x1 + x2) // 2, cy=(y1 + y2) // 2, x1=x1, y1=y1, x2=x2, y2=y2)
        out.append(a)
    return out


def keyboard_shown():
    m = re.search(r"mInputShown=(\w+)", sh(["shell", "dumpsys", "input_method"]))
    return m.group(1) if m else "?"


def tap(x, y, wait=1.6):
    sh(["shell", "input", "tap", str(x), str(y)])
    time.sleep(wait)


def find(ns, val):
    return [n for n in ns if n.get("text") == val]


def on_settings_page(ns=None):
    """设置页 ⇔ 顶栏「完成」在层级里。拍照页只有 调试/设置，没有「完成」。"""
    ns = ns if ns is not None else nodes()
    return any(n.get("text") == HEADER_DONE for n in ns)


def ensure_settings_page():
    """不在设置页就点「设置」进去（脚本可重复运行）。"""
    for _ in range(4):
        ns = nodes()
        if on_settings_page(ns):
            return True
        entry = find(ns, "设置")
        if not entry:
            sh(["shell", "am", "start", "-n", f"{PKG}/.MainActivity"])
            time.sleep(3)
            continue
        tap(entry[0]["cx"], entry[0]["cy"], wait=2.0)
    return on_settings_page()


def visible_fields():
    return sorted([n for n in nodes() if n.get("class") == "android.widget.EditText"],
                  key=lambda n: n["y1"])


def tap_blank():
    """点一个"没人处理点击"的位置收键盘。

    首选固定顶栏的标题文字：它永远可见（不随内容滚动），且顶栏 Row 上挂着
    detectTapGestures(onTap = dismissKeyboard)，点标题文字正好命中。
    说明文字（BLANK_ANCHOR）可见时优先点它 —— 离输入区更近，不惊动顶栏。
    """
    ns = nodes()
    info = [n for n in ns
            if n.get("clickable") == "false" and BLANK_ANCHOR in n.get("text", "")]
    if info:
        tap(info[0]["cx"], info[0]["cy"])
        return
    title = [n for n in ns
             if n.get("text") == HEADER_TITLE and n.get("clickable") == "false"]
    if title:
        t = title[0]
        # 点标题右半段的空白处，别点在文字正中间（虽然文字本身也不消费点击）
        tap(min(t["x2"] - 40, t["cx"] + 150), t["cy"])
        return
    tap(540, 400)  # 兜底，理论上走不到


def tap_field(idx):
    """点第 idx 个输入框（0=Base URL / 1=模型名 / 2=API Key）。
    键盘会遮住下方输入框，层级里看不到 —— 定位不到时先收键盘再定位。"""
    edits = visible_fields()
    if len(edits) <= idx:
        tap_blank()
        edits = visible_fields()
    if len(edits) <= idx:
        return False
    tap(edits[idx]["cx"], edits[idx]["cy"])
    return True


def app_visible_bottom():
    """App 内容区可见的底边 y（键盘占掉下面的部分之后）；拿不到返回 None。

    `mInputShown=true` 只说明键盘在，不说明它盖住了谁 —— 要断言"顶栏没被压住"
    得知道 App 实际还能看到多少。取输入法窗口的 `mGivenContentInsets` 下边距
    （App 会为键盘让出这块高度），退路是键盘的 `touchable region` 上边界。
    """
    out = sh(["shell", "dumpsys", "window", "windows"])
    blocks = out.split("Window #")
    for b in blocks:
        if "InputMethod}:" not in b:
            continue
        m = re.search(r"mGivenContentInsets=\[\d+,(\d+)\]", b)
        if m:
            h = re.search(r"Physical size: \d+x(\d+)", sh(["shell", "wm", "size"]))
            if h:
                return int(h.group(1)) - int(m.group(1))
        m = re.search(r"touchable region=SkRegion\(\(0,(\d+),", b)
        if m:
            return int(m.group(1))
    return None


def scroll_up():
    """滚回上方。**正常情况下不该需要它** —— 顶栏是固定的（2026-10-15 改）。

    仅作为第 5 步失败后让第 6 步仍可执行的退路：一旦这里被用到，
    说明顶栏又开始跟内容一起滚了，第 5 步会如实报错。
    """
    for _ in range(3):
        sh(["shell", "input", "swipe", "540", "700", "540", "1900", "300"])
        time.sleep(1.0)
        if find(nodes(), "完成"):
            return True
    return bool(find(nodes(), "完成"))


results = []
for rnd in range(1, ROUNDS + 1):
    print(f"--- 第 {rnd} 轮 ---")
    if not ensure_settings_page():
        print("!! 进不了设置页，中止")
        break

    if not tap_field(0):
        print("!! 定位不到输入框，中止")
        break
    ok = keyboard_shown() == "true"
    results.append(("1 点 Base URL 输入框 → 弹出", ok))
    print(f"   {'OK ' if ok else '!! '}1 点 Base URL 输入框 → 弹出")

    tap_blank()
    ok = keyboard_shown() == "false"
    results.append(("2 点空白处 → 收起", ok))
    print(f"   {'OK ' if ok else '!! '}2 点空白处 → 收起")

    if not tap_field(0):
        print("!! 定位不到输入框，中止")
        break
    card = find(nodes(), "DeepSeek")
    if card:
        tap(card[0]["cx"], card[0]["cy"])
    ok = keyboard_shown() == "false"
    results.append(("3 点端点卡片 → 收起", ok))
    print(f"   {'OK ' if ok else '!! '}3 点端点卡片 → 收起")

    ok = tap_field(1)
    ok = ok and keyboard_shown() == "true"
    results.append(("4 点模型名输入框 → 弹出（不该误伤）", ok))
    print(f"   {'OK ' if ok else '!! '}4 点模型名输入框 → 弹出")

    # 5) 键盘弹起时，固定顶栏必须在视口内、且不被键盘压住（本轮改动的回归点）
    kb_before = keyboard_shown()
    header = find(nodes(), "完成")
    bottom = app_visible_bottom()
    if header and kb_before == "true":
        h = header[0]
        ok = h["y1"] >= 0 and (bottom is None or h["y2"] <= bottom)
        results.append((f"5 顶栏固定在视口内（完成 y={h['y1']}~{h['y2']} / 可视底={bottom}）", ok))
        print(f"   {'OK ' if ok else '!! '}5 顶栏固定在视口内"
              f"（完成 y={h['y1']}~{h['y2']} / 可视底={bottom}）")
    else:
        results.append((f"5 顶栏固定在视口内（键盘={kb_before}，完成={bool(header)}）", False))
        print(f"   !! 5 顶栏固定在视口内（键盘={kb_before}，找得到完成={bool(header)}）")

    # 6) 键盘开着直接点「完成」→ 收键盘 + 回到拍照页
    if not header and not scroll_up():
        print(f"   SKIP 6 定位不到「完成」，无法点击")
    else:
        done = find(nodes(), "完成")
        tap(done[0]["cx"], done[0]["cy"], wait=2.2)
        ok = keyboard_shown() == "false"
        results.append((f"6 点「完成」→ 收起（点击前键盘={kb_before}）", ok))
        print(f"   {'OK ' if ok else '!! '}6 点「完成」→ 收起（点击前键盘={kb_before}）")
        back = not on_settings_page()
        results.append(("6 点「完成」→ 回到拍照页", back))
        print(f"   {'OK ' if back else '!! '}6 点「完成」→ 回到拍照页")

passed = sum(1 for _, ok in results if ok)
print(f"\n合计 {passed}/{len(results)} 项通过")
sys.exit(0 if results and passed == len(results) else 2)
