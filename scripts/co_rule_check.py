"""用真实语料回归 `status=incomplete` 判据（纯规则，不需要客户端在跑）。

规则（对应 `capture/OutputCapture.Session#looksInProgress`）：
    一行真数据都没有（所有非空行都是"进行中占位"或"分页页脚"） → incomplete

用法:
    python co_rule_check.py [语料目录]     # 默认 run/，找不到就退回当前目录

期望结果（2026-09-14 实测）:
    * 有截断的那轮 553 页扫描 → 标出 5 页（116/117/118/130/137，只有占位行 = 真截断）
    * 修复后重跑的 553 页扫描 → 标出 0 页（零截断）
    * 语料里的「占位 + 一句结论」  → **不**应被标出（那是正常收尾）
"""
import glob
import json
import os
import re
import sys

MARKERS = ["searching", "正在搜索", "please wait", "请稍等", "loading", "正在加载"]
# 中英两种语序："Page 1/6" / "第 1/6 页" —— 只写"页\s*\d+/\d+"会漏掉后者
FOOTER = re.compile(r"page\s*\d+\s*/\s*\d+|\d+\s*/\s*\d+\s*页", re.I)


def is_non_data(line):
    """该行是否只是"非数据行"（进行中占位 / 分页页脚），即不承载查询结果。"""
    low = line.lower()
    return any(m in low for m in MARKERS) or bool(FOOTER.search(line))


def is_incomplete(text):
    """当前判据：所有非空行都是非数据行，且至少有一行。"""
    saw = False
    for raw in text.split("\n"):
        line = raw.strip()
        if not line:
            continue
        saw = True
        if not is_non_data(line):
            return False
    return saw


def old_judge(text):
    """另一种更宽松的判据：含关键词且行数 ≤2（仅用于对比误报数量）。"""
    return bool(text) and len(text.split("\n")) <= 2 and any(m in text.lower() for m in MARKERS)


def corpus_files(root):
    files = sorted(glob.glob(os.path.join(root, "v4-raw-*.json")))
    if not files and root != ".":
        files = sorted(glob.glob("v4-raw-*.json"))
    return files


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "run"
    files = corpus_files(root)
    if not files:
        print(f"没找到 v4-raw-*.json（找过 {root}/ 和当前目录）—— 先在运行时工作目录跑一次 co_v4.py")
        return 2

    bad = 0
    for path in files:
        data = json.load(open(path, encoding="utf-8"))
        new = [x for x in data if is_incomplete(x.get("text") or "")]
        old = [x for x in data if old_judge(x.get("text") or "")]
        print(f"{path}: {len(data)} 页 / 新判据标出 {len(new)} / 旧判据标出 {len(old)}")
        for x in new:
            print(f"   [新] {x['command']}  lines={len((x.get('text') or '').splitlines())}  "
                  f"text={(x.get('text') or '').strip()[:60]!r}")
        for x in old:
            if x not in new:
                print(f"   [旧误报→已修] {x['command']}  "
                      f"text={(x.get('text') or '').strip()[:80]!r}")

    # 两端样本：结论必须 ok，纯占位必须 incomplete
    samples = [
        ('占位 + 一句结论（查无此人）',
         '[Server] 正在搜索，请稍候...\n[Server] 玩家 "Someone" 未找到。', False),
        ('占位 + 一句结论（数据库繁忙）',
         '[Server] 正在搜索，请稍等...\n[Server] 数据库繁忙，请稍后重试。', False),
        ('只有占位行', '[Server] 正在搜索，请稍等...', True),
        ('占位 + 页脚（结果没到）', '[Server] 正在搜索，请稍等...\n----- 第 1/6 页 -----', True),
        ('正常单行结论（无占位）', '[Server] 玩家 "x" 未找到。', False),
    ]
    print()
    for title, text, want in samples:
        got = is_incomplete(text)
        mark = "✅" if got == want else "❌"
        if got != want:
            bad += 1
        print(f"{mark} {title}: incomplete={got}（期望 {want}）")

    print("\n==== 规则回归 " + ("通过" if bad == 0 else f"失败 {bad} 项") + " ====")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
