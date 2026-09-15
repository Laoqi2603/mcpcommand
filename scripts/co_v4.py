"""大数据量分页查询验收：分页完整性 + 静默/超时参数调优

用法:
    python co_v4.py [时间范围] [重复次数] [quiet_ms] [timeout_ms]
    默认:          t:30d       3            250        5000

做四件事:
    1) 用给定的 quiet_ms / timeout_ms 翻完 <range> 的全部页（默认上限 80 页），
       检查每页是否出现"只有 searching… 没有结果表头"= 静默窗口太短（静默窗口过短）
    2) 汇总「事件行 ↔ 坐标行」配对（每个会话事件后跟一行坐标）
    3) 同一查询连跑 N 次（剥离颜色码 + 归一化相对时间）必须完全一致（一致性回归）
    4) 汇总 mc_command 的结构化结果: duplicates_dropped（跨通道去重，正常）/ late_dropped（迟到丢弃，异常）
最后打印调参建议。

输出: run/v4-report.txt + run/v4-raw-<ts>.json
"""
import json
import re
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:25888"
REPORT = "v4-report.txt"
SECTION = re.compile(r"\u00a7.")
# 相对时间： 0.87/m ago  或  0.87分钟前
RELTIME = re.compile(r"\d+(?:\.\d+)?\s*(?:/[smhd]\s*(?:ago|前)|(?:秒|分钟|小时|天)\s*前)", re.IGNORECASE)
PAGE = re.compile(r"(?:Page|第)\s*(\d+)\s*/\s*(\d+)", re.IGNORECASE)
LOGIN = re.compile(r"\+\s+(\S+?)\s+(?:logged in|已登录|登入|登录)", re.IGNORECASE)
LOGOUT = re.compile(r"-\s+(\S+?)\s+(?:logged out|已离开|离开|登出|退出)", re.IGNORECASE)
COORD = re.compile(r"\^\s*\(x(-?\d+)/y(-?\d+)/z(-?\d+)/(\S+?)\)")

_lines = []
_raw = []
_initialized = False


def log(m=""):
    _lines.append(m)
    print(m.encode("ascii", "replace").decode("ascii"))


def http(payload, timeout=180):
    req = urllib.request.Request(BASE + "/mcp", data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def ensure_initialized():
    global _initialized
    if _initialized:
        return
    http({"jsonrpc": "2.0", "id": 0, "method": "initialize",
          "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                     "clientInfo": {"name": "co-v4", "version": "1.0"}}})
    _initialized = True


def run_cmd(command, quiet_ms, timeout_ms):
    t0 = time.time()
    resp = http({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                 "params": {"name": "mc_command", "arguments": {
                     "command": command, "quiet_ms": quiet_ms, "timeout_ms": timeout_ms}}})
    dt = time.time() - t0
    result = resp.get("result", {})
    text = result.get("content", [{}])[0].get("text", "")
    struct = result.get("structuredContent") or {}
    results = struct.get("results") or [{}]
    first = results[0] if results else {}
    _raw.append({"command": command, "seconds": round(dt, 2), "text": text, "structured": struct})
    return text, dt, first


def clean(t):
    return SECTION.sub("", t)


def stats(text):
    c = clean(text)
    n = RELTIME.sub("T ago", c)
    low = c.lower()
    # 只有当"出现 searching 但既没有结果表头、也没有任何结论性内容"时才算截断。
    # 「未找到 / 没有找到 / no results」都是**完整回答**，不能判成截断。
    conclusive = any(k in low or k in c for k in
                     ["lookup results", "查询结果", "no results", "not found", "未找到", "没有找到", "无结果"])
    return {
        "lines": len(text.splitlines()),
        "logins": len(LOGIN.findall(c)),
        "logouts": len(LOGOUT.findall(c)),
        "coords": len(COORD.findall(c)),
        "truncated": ("searching" in low or "正在搜索" in c) and not conclusive,
        "not_found": any(k in low or k in c for k in ["not found", "未找到", "没有找到", "无结果"]),
        "empty": not c.strip(),
        "norm": n,
        "page": (PAGE.search(c).groups() if PAGE.search(c) else None),
    }


def main():
    rng = sys.argv[1] if len(sys.argv) > 1 else "t:30d"
    repeat = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    quiet = int(sys.argv[3]) if len(sys.argv) > 3 else 250
    timeout = int(sys.argv[4]) if len(sys.argv) > 4 else 5000
    max_pages = int(sys.argv[6]) if len(sys.argv) > 6 else 120
    max_seconds = int(sys.argv[5]) if len(sys.argv) > 5 else 420

    ensure_initialized()
    # 支持两种用法：
    #   python co_v4.py t:30d 3 250 5000                     → 测 /co lookup a:session t:30d
    #   python co_v4.py "/co lookup u:xxx a:-block t:30d" 3  → 直接给它一条完整 lookup 命令
    if rng.startswith("/"):
        lookup_cmd = rng
        label = lookup_cmd
    else:
        lookup_cmd = f"/co lookup a:session {rng}"
        label = f"a:session {rng}"

    log("=" * 78)
    log(f"V4 大数据量验收   {time.strftime('%Y-%m-%d %H:%M:%S')}")
    log(f"查询={label}")
    log(f"重复={repeat}  quiet_ms={quiet}  timeout_ms={timeout}  最大页数={max_pages}  时间预算={max_seconds}s")
    log("=" * 78)

    log("\n### 1) 第 1 页（拿总页数）")
    first, dt, st0 = run_cmd(lookup_cmd, quiet, timeout)
    s = stats(first)
    log(f"[{dt:.1f}s] 行数={s['lines']} 登录={s['logins']} 登出={s['logouts']} 坐标={s['coords']} "
        f"页码={s['page']} 截断={s['truncated']} 空={s['empty']}")
    log(f"结构化: status={st0.get('status')} duplicates_dropped={st0.get('duplicates_dropped')} "
        f"late_dropped={st0.get('late_dropped')}")

    total_pages = int(s["page"][1]) if s["page"] else 1
    log(f"总页数 = {total_pages}")

    log(f"\n### 2) 翻页（1..{min(total_pages, max_pages)}，时间预算 {max_seconds}s）")
    pages = {1: s}
    truncated_pages = [1] if s["truncated"] else []      # 第 1 页也可能被截断，别漏
    empty_pages = [1] if s["empty"] else []
    slowest = dt
    started = time.time()
    stopped_early = False
    for p in range(2, min(total_pages, max_pages) + 1):
        if time.time() - started > max_seconds:
            log(f"  ⏱ 到达时间预算 {max_seconds}s，停在第 {p - 1} 页（共 {total_pages} 页）")
            stopped_early = True
            break
        text, dt, st = run_cmd(f"/co page {p}", quiet, timeout)
        ps = stats(text)
        pages[p] = ps
        slowest = max(slowest, dt)
        if ps["truncated"]:
            truncated_pages.append(p)
        if ps["empty"]:
            empty_pages.append(p)
        log(f"  p{p:>3}: [{dt:>5.1f}s] 行数={ps['lines']:>3} 事件={ps['logins'] + ps['logouts']:>3} "
            f"坐标={ps['coords']:>3} 页码={ps['page']} {'⚠️截断' if ps['truncated'] else ''}"
            f"{' ⚠️空' if ps['empty'] else ''} late={st.get('late_dropped')}")

    log("\n### 3) 汇总")
    ev = sum(p["logins"] + p["logouts"] for p in pages.values())
    co = sum(p["coords"] for p in pages.values())
    tot_lines = sum(p["lines"] for p in pages.values())
    if ev > 0:
        log(f"  共 {len(pages)} 页：会话事件 {ev} / 坐标行 {co} → "
            f"{'配对完整 ✅' if ev == co else f'不配对 ❌（差 {ev - co}，去重吞行）'}")
        log(f"  登录 {sum(p['logins'] for p in pages.values())} / 登出 {sum(p['logouts'] for p in pages.values())}"
            "（不等说明有人还在线/会话未闭合）")
    else:
        # 非 session 类查询（a:-block / a:container 等）：按行数与坐标行报告
        log(f"  共 {len(pages)} 页：总行数 {tot_lines}，坐标行 {co}")
        log("  （非 session 查询不做『事件↔坐标』配对断言；下方一致性才是关键指标）")
    log(f"  截断页: {truncated_pages or '无 ✅'}    空页: {empty_pages or '无'}    最慢单页 {slowest:.1f}s")

    log(f"\n### 4) 一致性：同一查询连跑 {repeat} 次（归一化后必须一致）")
    norms = []
    for i in range(1, repeat + 1):
        text, dt, st = run_cmd(lookup_cmd, quiet, timeout)
        n = stats(text)
        norms.append(n["norm"])
        log(f"  第 {i} 次: [{dt:>5.1f}s] 行数={n['lines']} 坐标={n['coords']} "
            f"截断={n['truncated']} late={st.get('late_dropped')}")
    same = len(set(norms)) == 1
    log(f"  >>> {'一致 ✅' if same else '不一致 ❌（去重吞行/静默过早收口/迟到串位）'}（{len(set(norms))} 种结果）")

    log("\n### 5) 调参建议")
    if truncated_pages:
        log(f"  ⚠️ 有 {len(truncated_pages)} 页只收到 'searching…' → 静默窗口太短，"
            f"建议 quiet_ms 提到 {max(quiet * 3, 1000)} 后重跑")
    if empty_pages:
        log(f"  ⚠️ 有 {len(empty_pages)} 页为空 → 可能是 timeout_ms={timeout} 太短，建议提到 {max(timeout * 2, 15000)}")
    if slowest > timeout * 0.8:
        log(f"  ⚠️ 最慢单页 {slowest:.1f}s 已接近 timeout_ms={timeout} → 建议提高超时")
    if stopped_early:
        log(f"  ⚠️ 因时间预算提前停止（未翻完 {total_pages} 页）—— 想翻完请调大第 5 个参数或分批")
    if not (truncated_pages or empty_pages) and same and not stopped_early:
        log(f"  ✅ 本参数组合（quiet_ms={quiet}, timeout_ms={timeout}）下：无截断、无空页、结果一致 —— "
            f"可以把这组值作为该数据量的默认建议值")

    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(_lines) + "\n")
    raw = f"v4-raw-{time.strftime('%Y%m%d-%H%M%S')}.json"
    json.dump(_raw, open(raw, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    log(f"\n报告: {REPORT}   原始: {raw}")


if __name__ == "__main__":
    main()
