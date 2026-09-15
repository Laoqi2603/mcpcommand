"""容器交易分析：翻完 /co lookup u:<玩家> a:container 的所有页，统计物品并检查附魔/自定义名

用法:
    python co_container.py [玩家] [时间范围] [最大页数]
    默认:          Steve   t:1d   30      ← 玩家名换成你服务器上真实存在的

关注点:
    1) 逐页翻完，统计每个物品的 放入(added) / 取出(removed) 次数与数量
    2) 事件行 ↔ 坐标行 是否配对（丢行检测）
    3) 物品行里是否带**附魔/自定义名**信息 —— 模组的 hover 通道（extractHoverItems/formatItemStackNbt）
       应该把物品 tooltip 追加成 `  [名字] {sharpness=5, unbreaking=3} dur:12/1561` 这种
    4) 同一页连查两次是否一致（迟到输出串位）
输出:
    run/co-container-report.txt + run/co-container-raw-<ts>.json
"""
import hashlib
import json
import re
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:25888"
SECTION_RE = re.compile(r"\u00a7.")
RELTIME_RE = re.compile(r"\d+(?:\.\d+)?\s*(?:/[smhd]\s*(?:ago|前)|(?:秒|分钟|小时|天)\s*前)", re.IGNORECASE)
PAGE_RE = re.compile(r"(?:Page|第)\s*(\d+)\s*/\s*(\d+)", re.IGNORECASE)
# 事件行样例：0.83/m ago + Steve added x1 netherite_axe.
EVENT_RE = re.compile(
    r"^(?P<t>\S+\s*(?:ago|前))\s+(?P<sign>[+-])\s+(?P<user>\S+)\s+"
    r"(?P<verb>added|removed|took|put|withdrew|deposited|inserted|extracted|放入|取出|添加|移除|存入|拿出)\s+"
    r"x(?P<count>\d+)\s+(?P<item>.+?)[.。]?$", re.IGNORECASE)
COORD_RE = re.compile(r"^\s*\^\s*\(x(-?\d+)/y(-?\d+)/z(-?\d+)/(\S+?)\)")
# 模组 hover 通道追加的富文本：以两个空格 + [ 开头
HOVER_RE = re.compile(r"^\s{2,}\[.+\]")

_lines, _raw = [], []


def log(m=""):
    _lines.append(m)
    print(m.encode("ascii", "replace").decode("ascii"))


def clean(t):
    return SECTION_RE.sub("", t)


def ensure_initialized():
    """规范的 MCP 客户端要先 initialize（模组会校验）"""
    try:
        http({"jsonrpc": "2.0", "id": 0, "method": "initialize",
              "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                         "clientInfo": {"name": "mcpcommand-debug", "version": "0.1"}}})
    except Exception as e:
        print("initialize failed:", e)


def http(payload, timeout=120):
    req = urllib.request.Request(BASE + "/mcp", data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def run_cmd(cmd):
    t0 = time.time()
    resp = http({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                 "params": {"name": "mc_command", "arguments": {"command": cmd}}})
    dt = time.time() - t0
    text = resp["result"]["content"][0]["text"]
    _raw.append({"command": cmd, "seconds": round(dt, 2), "text": text})
    return text, dt


def main():
    user = sys.argv[1] if len(sys.argv) > 1 else "Steve"
    rng = sys.argv[2] if len(sys.argv) > 2 else "t:1d"
    max_pages = int(sys.argv[3]) if len(sys.argv) > 3 else 30

    ensure_initialized()
    log("=" * 78)
    log(f"容器交易分析: u:{user} a:container {rng}")
    log(f"时间: {time.strftime('%Y-%m-%d %H:%M:%S')}   最大页数: {max_pages}")
    log("=" * 78)

    first, dt = run_cmd(f"/co lookup u:{user} a:container {rng}")
    m = PAGE_RE.search(clean(first))
    total = int(m.group(2)) if m else 1
    log(f"\n第 1 页: [{dt:.1f}s] 行数={len(first.splitlines())} 总页数={total}")

    pages = {1: first}
    for p in range(2, min(total, max_pages) + 1):
        text, dt = run_cmd(f"/co page {p}")
        pages[p] = text
    log(f"已抓取 {len(pages)} 页，用时合计 {sum(r['seconds'] for r in _raw):.1f}s")

    # ---- 解析 ----
    log("\n" + "=" * 78)
    log("解析结果")
    log("=" * 78)
    events = []          # (page, user, sign, count, item, has_hover)
    hover_lines = []
    pending = None
    problems = []
    for p in sorted(pages):
        body = [clean(l) for l in pages[p].splitlines() if l.strip()]
        for i, line in enumerate(body):
            if HOVER_RE.match(line):
                hover_lines.append((p, line.strip()))
                if pending:
                    events[-1] = events[-1][:-1] + (True,)
                continue
            em = EVENT_RE.match(line.strip())
            if em:
                if pending:
                    problems.append(f"p{p}: 事件 '{pending[4]}' 后面没跟坐标行（下一行是事件行）")
                ev = (p, em.group("user"), em.group("sign"), int(em.group("count")), em.group("item").strip(), False)
                events.append(ev)
                pending = ev
            elif COORD_RE.match(line):
                if not pending:
                    problems.append(f"p{p}: 坐标行孤立（前面没有事件行）: {line.strip()[:50]}")
                pending = None

    log(f"\n事件行总数: {len(events)}    坐标行未配对数: {len(problems)}")
    if hover_lines:
        log(f"\n★ 带 hover 富文本的行 {len(hover_lines)} 条（说明物品 tooltip 被抓到了）:")
        for p, l in hover_lines[:25]:
            log(f"   p{p}: {l}")
    else:
        log("\n⚠️ 没有任何 hover 富文本行 —— 即物品的『附魔/自定义名/NBT』没有被捕获到。")
        log("   （要么这些事件本身没挂 tooltip，要么模组的 extractHoverItems 没提取出来）")

    # 物品统计
    tally = {}
    for p, u, sign, cnt, item, hover in events:
        key = item
        d = tally.setdefault(key, {"added": 0, "removed": 0, "add_n": 0, "rem_n": 0, "hover": False, "users": set()})
        if sign == "+":
            d["added"] += 1
            d["add_n"] += cnt
        else:
            d["removed"] += 1
            d["rem_n"] += cnt
        d["hover"] = d["hover"] or hover
        d["users"].add(u)

    log("\n" + "=" * 78)
    log("物品清单（按事件数排序）")
    log("=" * 78)
    log(f"  {'放入次':>6} {'放入数':>6} {'取出次':>6} {'取出数':>6}  物品")
    for item, d in sorted(tally.items(), key=lambda kv: -(kv[1]["added"] + kv[1]["removed"])):
        log(f"  {d['added']:>6} {d['add_n']:>6} {d['removed']:>6} {d['rem_n']:>6}  {item}"
            + ("   ← 带 tooltip" if d["hover"] else ""))
    log(f"\n共 {len(tally)} 种物品，{len(events)} 笔交易")

    # ---- 一致性：同页连查两次 ----
    log("\n" + "=" * 78)
    log("一致性检查（快速翻页时迟到输出可能串位）")
    log("=" * 78)
    t1, _ = run_cmd(f"/co lookup u:{user} a:container {rng}")
    t2, _ = run_cmd(f"/co page 2")
    t3, _ = run_cmd(f"/co page 2")
    n = lambda t: [RELTIME_RE.sub("T ago", clean(l)).strip() for l in t.splitlines() if l.strip()]
    same = n(t2) == n(t3)
    log(f"  page2 连查两次: {'一致 ✅' if same else '不一致 ❌（可能有串位/新事件）'}")
    if not same:
        import difflib
        for line in difflib.unified_diff(n(t2), n(t3), "第1次", "第2次", lineterm="", n=0):
            if line.startswith(("+", "-")) and not line.startswith(("+++", "---")):
                log("    " + line)

    log("\n问题清单:")
    for p in problems[:20]:
        log("  ❌ " + p)
    if not problems:
        log("  ✅ 事件/坐标配对正常")

    with open("co-container-report.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(_lines) + "\n")
    raw = f"co-container-raw-{time.strftime('%Y%m%d-%H%M%S')}.json"
    json.dump(_raw, open(raw, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    log(f"\n报告: co-container-report.txt   原始: {raw}")


if __name__ == "__main__":
    main()
