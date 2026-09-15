"""分析 co_test.py 产出的原始返回：找出同一查询多次结果的具体差异 + 校验事件/坐标配对

用法:
    python co_diff.py co-raw-20260914-192804.json
"""
import difflib
import json
import re
import sys

SECTION_RE = re.compile(r"\u00a7.")
LOGIN_RE = re.compile(r"\+\s+(\S+?)\s+(?:logged in|已登录|登入|登录)", re.IGNORECASE)
LOGOUT_RE = re.compile(r"-\s+(\S+?)\s+(?:logged out|已离开|离开|登出|退出)", re.IGNORECASE)
COORD_RE = re.compile(r"\^\s*\(x(-?\d+)/y(-?\d+)/z(-?\d+)/(\S+?)\)")


def clean(t):
    return SECTION_RE.sub("", t)


def lines_of(t):
    return [l for l in clean(t).splitlines() if l.strip()]


def main():
    path = sys.argv[1]
    raw = json.load(open(path, encoding="utf-8"))
    by_cmd = {}
    for r in raw:
        by_cmd.setdefault(r["command"], []).append(r)

    print("=" * 78)
    print("同一查询多次结果差异分析（a:session lookup）")
    print("=" * 78)

    lookups = [(c, rs) for c, rs in by_cmd.items() if "lookup" in c]
    for cmd, rs in lookups:
        print(f"\n指令: {cmd}    共 {len(rs)} 次")
        if len(rs) < 2:
            continue
        base = lines_of(rs[0]["text"])
        for r in rs[1:]:
            other = lines_of(r["text"])
            if base == other:
                print(f"  · [{r['seconds']}s] 与第 1 次完全相同 ✅")
                continue
            print(f"  · [{r['seconds']}s] 与第 1 次不同 ❌  差异：")
            for line in difflib.unified_diff(base, other, "第1次", f"第{rs.index(r)+1}次", lineterm="", n=1):
                if line.startswith(("+", "-")) and not line.startswith(("+++", "---")):
                    print("      " + line)

    print("\n" + "=" * 78)
    print("分页结构校验：事件行 ↔ 坐标行 配对（登录事件应各带一行坐标）")
    print("=" * 78)
    pages = sorted((c for c in by_cmd if "page" in c), key=lambda c: int(re.search(r"\d+", c).group()))
    seq = []
    for c in pages:
        seq.extend(lines_of(by_cmd[c][0]["text"]))
    print(f"拼接 {len(pages)} 页，共 {len(seq)} 行：\n")
    pending_login = None
    problems = []
    for i, line in enumerate(seq):
        m_in = LOGIN_RE.search(line)
        m_out = LOGOUT_RE.search(line)
        m_co = COORD_RE.search(line)
        if m_in:
            if pending_login:
                problems.append(f"第 {i} 行：上一个登录事件 {pending_login} 还没等到坐标行，就来了新的登录事件")
            pending_login = (i, m_in.group(1))
            print(f"  [{i:3}] 登录  {line.strip()}")
        elif m_out:
            if pending_login:
                problems.append(f"第 {i} 行：登录事件 {pending_login} 缺坐标行（下一行是登出）")
                pending_login = None
            print(f"  [{i:3}] 登出  {line.strip()}")
        elif m_co:
            x, y, z, w = m_co.groups()
            if pending_login:
                print(f"  [{i:3}] 坐标  ({x},{y},{z},{w})   ← 配给第 {pending_login[0]} 行的 {pending_login[1]}")
                pending_login = None
            else:
                problems.append(f"第 {i} 行：坐标行 (x{x}/y{y}/z{z}/{w}) 孤立 —— 前面没有登录事件")
                print(f"  [{i:3}] 坐标  ({x},{y},{z},{w})   ← ⚠️ 孤立")
        else:
            print(f"  [{i:3}] 其它  {line.strip()[:70]}")
    if pending_login:
        problems.append(f"末尾登录事件 {pending_login} 缺坐标行")

    print("\n--- 问题清单 ---")
    if problems:
        for p in problems:
            print("  ❌ " + p)
    else:
        print("  ✅ 未发现配对问题")


if __name__ == "__main__":
    main()
