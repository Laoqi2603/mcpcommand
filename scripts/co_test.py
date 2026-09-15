"""查询指令回归测试：经 MCP -> mc_command 在真机上执行 /co 系列指令

用法:
    python co_test.py [时间范围] [重复次数] [最大页数]
    默认:          t:7d          3           12

做什么:
    1) /co version                          环境与权限自检（没 op 的话客户端会本地拒绝）
    2) /co lookup a:session <range>         抓第 1 页
    3) /co page 2..N                        逐页翻完
    4) 重复第 2 步 N 次                     一致性回归（一致性回归）
    5) 结构校验                             事件行/坐标行配对 + 进出平衡（统计口径自检）
输出:
    run/co-report.txt        人类可读报告
    run/co-raw-<时间戳>.json 每次调用的原始返回文本

注意:
    插件输出带 §x 颜色代码，解析前先剥离（strip_codes）。
"""
import hashlib
import json
import re
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:25888"
REPORT = "co-report.txt"
SECTION_RE = re.compile(r"\u00a7.")
# 输出用相对时间（0.75/m ago、2.1/h ago…），两次查询之间必然会变，
# 比较"结果是否一致"时要把时间归一化，否则会误判成丢行
RELTIME_RE = re.compile(r"\d+(?:\.\d+)?\s*(?:/[smhd]\s*(?:ago|前)|(?:秒|分钟|小时|天)\s*前)", re.IGNORECASE)
PAGE_RE = re.compile(r"(?:Page|第)\s*(\d+)\s*/\s*(\d+)", re.IGNORECASE)
LOGIN_RE = re.compile(r"\+\s+(\S+?)\s+(?:logged in|已登录|登入|登录)", re.IGNORECASE)
LOGOUT_RE = re.compile(r"-\s+(\S+?)\s+(?:logged out|已离开|离开|登出|退出)", re.IGNORECASE)
COORD_RE = re.compile(r"\^\s*\(x(-?\d+)/y(-?\d+)/z(-?\d+)/(\S+?)\)")

_lines = []
_raw = []


def log(msg=""):
    _lines.append(msg)
    print(msg.encode("ascii", "replace").decode("ascii"))


def strip_codes(text):
    """去掉 Minecraft 的 § 颜色/格式代码"""
    return SECTION_RE.sub("", text)


def ensure_initialized():
    """规范的 MCP 客户端要先 initialize（模组会校验）"""
    try:
        http({"jsonrpc": "2.0", "id": 0, "method": "initialize",
              "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                         "clientInfo": {"name": "mcpcommand-debug", "version": "0.1"}}})
    except Exception as e:
        print("initialize failed:", e)


def http(payload, timeout=90):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(BASE + "/mcp", data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, json.loads(r.read().decode("utf-8"))


def run_cmd(command):
    payload = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
               "params": {"name": "mc_command", "arguments": {"command": command}}}
    t0 = time.time()
    status, resp = http(payload)
    dt = time.time() - t0
    try:
        text = resp["result"]["content"][0]["text"]
    except (KeyError, IndexError):
        text = f"<<非预期响应: {json.dumps(resp, ensure_ascii=False)}>>"
    _raw.append({"command": command, "http": status, "seconds": round(dt, 2), "text": text})
    return text, dt


def page_info(text):
    m = PAGE_RE.search(strip_codes(text))
    return f"{m.group(1)}/{m.group(2)}" if m else "-"


def stats(text):
    clean = strip_codes(text)
    normalized = RELTIME_RE.sub("T ago", clean)
    return {
        "lines": len(text.splitlines()),
        "logins": len(LOGIN_RE.findall(clean)),
        "logouts": len(LOGOUT_RE.findall(clean)),
        "coords": len(COORD_RE.findall(clean)),
        "no_results": "no results" in clean.lower() or "没有找到" in clean,
        # 只有 "searching..." 没有结果表头 —— 静默窗口过早收口
        "truncated": "searching" in clean.lower() and "lookup results" not in clean.lower(),
        "md5": hashlib.md5(text.encode("utf-8")).hexdigest()[:8],
        "md5_norm": hashlib.md5(normalized.encode("utf-8")).hexdigest()[:8],
        "norm": normalized,
    }


def main():
    rng = sys.argv[1] if len(sys.argv) > 1 else "t:7d"
    repeat = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    max_pages = int(sys.argv[3]) if len(sys.argv) > 3 else 12

    ensure_initialized()
    log("=" * 78)
    log("查询指令回归测试（经 MCP mc_command 真机执行）")
    log(f"时间     : {time.strftime('%Y-%m-%d %H:%M:%S')}")
    log(f"范围     : {rng}    重复次数: {repeat}    最大页数: {max_pages}")
    log("=" * 78)

    log("\n### 1) /co version —— 环境与权限自检")
    text, dt = run_cmd("/co version")
    log(strip_codes(text))

    log(f"\n### 2) /co lookup a:session {rng} —— 第 1 页")
    first, dt = run_cmd(f"/co lookup a:session {rng}")
    s1 = stats(first)
    log(f"[{dt:.1f}s] 行数={s1['lines']} 页码={page_info(first)} 登录={s1['logins']} 登出={s1['logouts']} 坐标={s1['coords']}")
    log("--- 原文（剥离颜色码）---")
    for line in strip_codes(first).splitlines():
        log("    " + line)

    m = PAGE_RE.search(strip_codes(first))
    total_pages = int(m.group(2)) if m else 1
    log(f"\n### 3) 翻页：页脚报告共 {total_pages} 页，逐页抓取（上限 {max_pages}）")
    pages = {"1": s1}
    for p in range(2, min(total_pages, max_pages) + 1):
        text, dt = run_cmd(f"/co page {p}")
        st = stats(text)
        pages[str(p)] = st
        body = [l for l in strip_codes(text).splitlines() if l.strip()]
        log(f"  /co page {p}: [{dt:.1f}s] 行数={st['lines']} 页码={page_info(text)} "
            f"登录={st['logins']} 登出={st['logouts']} 坐标={st['coords']}")
        log(f"      首行: {body[0][:70] if body else '(空)'}")
        log(f"      末行: {body[-1][:70] if body else '(空)'}")

    log("\n### 4) 结构校验（统计口径自检）")
    tot_login = sum(p["logins"] for p in pages.values())
    tot_logout = sum(p["logouts"] for p in pages.values())
    tot_coord = sum(p["coords"] for p in pages.values())
    tot_events = tot_login + tot_logout
    log(f"  汇总（{len(pages)} 页）: 登录={tot_login}  登出={tot_logout}  事件合计={tot_events}  坐标行={tot_coord}")
    log(f"  · 事件行 ↔ 坐标行配对: {'完整 ✅' if tot_events == tot_coord else f'不完整 ❌（事件 {tot_events} vs 坐标 {tot_coord}，差 {tot_events - tot_coord} 行 → 去重吞行）'}")
    log("    （每个会话事件后面都跟一行坐标，所以这两个数应当相等）")
    log(f"  · 进出平衡: {'平衡 ✅' if tot_login == tot_logout else f'不平衡（差 {tot_login - tot_logout}）— 有人还在线/会话未闭合时属正常'}")

    log(f"\n### 5) 一致性回归：同一查询连跑 {repeat} 次（一致性回归）")
    log("    比较口径：剥离 §颜色码 + 相对时间 (0.75/m ago) 归一化为 T ago（时间本身必然变化，不算差异）")
    runs = []
    for i in range(1, repeat + 1):
        text, dt = run_cmd(f"/co lookup a:session {rng}")
        st = stats(text)
        runs.append(st)
        log(f"  第 {i} 次: [{dt:.1f}s] 行数={st['lines']} 页码={page_info(text)} "
            f"登录={st['logins']} 登出={st['logouts']} 坐标={st['coords']} "
            f"md5(归一化)={st['md5_norm']}{'  ⚠️只有searching没有结果' if st['truncated'] else ''}")
    uniq = {r["md5_norm"] for r in runs}
    uniq_lines = sorted({r["lines"] for r in runs})
    uniq_events = sorted({(r["logins"], r["logouts"]) for r in runs})
    ok = len(uniq) == 1
    log(f"\n  >>> 结论: {'一致 ✅' if ok else '不一致 ❌'}"
        f"（归一化后 {len(uniq)} 种结果 / 行数集合 {uniq_lines} / (登录,登出) 集合 {uniq_events}）")
    if not ok:
        log("      结果会变 = 去重吞行 / 静默过早收口 / 迟到输出串位")
    if any(r["truncated"] for r in list(pages.values()) + runs):
        log("      ⚠️ 出现过『只有 searching... 没有结果表头』—— 静默窗口过短")

    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(_lines) + "\n")
    raw_path = f"co-raw-{time.strftime('%Y%m%d-%H%M%S')}.json"
    with open(raw_path, "w", encoding="utf-8") as f:
        json.dump(_raw, f, ensure_ascii=False, indent=2)
    log(f"\n报告: {REPORT}    原始返回: {raw_path}")


if __name__ == "__main__":
    main()
