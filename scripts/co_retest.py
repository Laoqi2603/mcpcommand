"""对"被截断的页"做多组 quiet_ms 复测，定位需要多大的静默窗口

用法:
    python co_retest.py [页列表] [quiet_ms列表]
    默认: 116,117,118,119,130,137   250,800,1500,3000

前置：先跑过一次同一条 lookup（翻页上下文按玩家保存在服务端）。
输出：run/retest-report.txt
"""
import json
import os
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:25888"
REPORT = "retest-report.txt"
SECTION = "\u00a7"
#: 翻页上下文按玩家保存，这里用哪个玩家查都行 —— 换成你服务器上真实存在的
PLAYER = os.environ.get("CO_PLAYER", "Steve")

pages = [int(x) for x in (sys.argv[1].split(",") if len(sys.argv) > 1 else
                          ["116", "117", "118", "119", "130", "137"])]
quiets = [int(x) for x in (sys.argv[2].split(",") if len(sys.argv) > 2 else
                           ["250", "800", "1500", "3000"])]

_lines = []


def log(m=""):
    _lines.append(m)
    print(m.encode("ascii", "replace").decode("ascii"))


def http(payload, timeout=120):
    req = urllib.request.Request(BASE + "/mcp", data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def run_cmd(cmd, quiet_ms, timeout_ms):
    t0 = time.time()
    resp = http({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                 "params": {"name": "mc_command", "arguments": {
                     "command": cmd, "quiet_ms": quiet_ms, "timeout_ms": timeout_ms}}})
    dt = time.time() - t0
    res = resp.get("result", {})
    return res.get("content", [{}])[0].get("text", ""), dt


def is_truncated(text):
    c = text.replace(SECTION, "")
    conclusive = any(k in c for k in ["查询结果", "lookup results", "未找到", "没有找到", "无结果", "no results"])
    return ("正在搜索" in c or "searching" in c.lower()) and not conclusive


def main():
    # 先 initialize + 重建翻页上下文（重跑一次 lookup，保证 /co page N 有效）
    http({"jsonrpc": "2.0", "id": 0, "method": "initialize",
          "params": {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "retest", "version": "1"}}})
    log(f"重建翻页上下文：/co lookup u:{PLAYER} a:container t:30d")
    run_cmd(f"/co lookup u:{PLAYER} a:container t:30d", 250, 8000)
    log("")
    log("=" * 78)
    log(f"截断页复测：页={pages}   quiet_ms 取值={quiets}")
    log("=" * 78)

    summary = {}
    for q in quiets:
        oks, bad = [], []
        detail = []
        for p in pages:
            text, dt = run_cmd(f"/co page {p}", q, 10000)
            tr = is_truncated(text)
            (bad if tr else oks).append(p)
            detail.append(f"p{p}={'✗' if tr else 'ok'}({dt:.1f}s,{len(text.splitlines())}行)")
        summary[q] = (len(oks), len(bad))
        log(f"\nquiet_ms={q:>5}  完整 {len(oks)}/{len(pages)} 页" + ("  ✅ 全部拿到" if not bad else f"  仍截断: {bad}"))
        log("   " + "  ".join(detail))

    log("\n" + "=" * 78)
    log("结论")
    log("=" * 78)
    best = None
    for q in quiets:
        ok, bad = summary[q]
        if bad == 0 and best is None:
            best = q
        log(f"  quiet_ms={q:>5}: 完整 {ok}/{len(pages)}")
    if best:
        log(f"\n  >>> 要拿全这 {len(pages)} 页，静默窗口至少要 {best}ms（当前默认 250ms 会丢页）")
    else:
        log(f"\n  >>> 即便 {max(quiets)}ms 仍有页截断 —— 需要同时提高 timeout_ms，或改用明确的结束标志")

    open(REPORT, "w", encoding="utf-8").write("\n".join(_lines) + "\n")
    log(f"\n报告: {REPORT}")


if __name__ == "__main__":
    main()
