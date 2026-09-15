"""冒烟测试：核对 `serverInfo.version` + 真机指令的 `status` 判定。

用法:
    python co_smoke.py [port] [期望版本] [测试玩家名]

    port       默认 25888
    期望版本    给了就断言，不符直接失败（例如 1.0.0）
    测试玩家名   默认 Steve —— **换成你服务器上真实存在的玩家名**，否则"查到数据"那两条只会返回"未找到"

重点验证 `incomplete` 判据：
  * "占位行 + 一句结论"（查无此人）→ 必须 `ok`：这是正常收尾，不是"没抓到"
  * "只有占位行"                    → 必须 `incomplete`（占位保护不能失效）

"只有占位行"这一支在本地服务器上太快（整页 600ms 内就回来了），压 `timeout_ms` 复现不出来，
所以改用历史语料做规则级回归 → 见 `co_rule_check.py`。
"""
import json
import sys
import time
import urllib.request

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 25888
EXPECT_VERSION = sys.argv[2] if len(sys.argv) > 2 else None
#: 测试用的玩家名：换成你服务器上真实存在的玩家，脚本才有"有数据"的样本可查
PLAYER = sys.argv[3] if len(sys.argv) > 3 else "Steve"
BASE = f"http://127.0.0.1:{PORT}"
SESSION = {"id": None}
PAGE = "第\\s*\\d+\\s*/\\s*\\d+\\s*页|Page\\s*\\d+\\s*/\\s*\\d+"


def post(payload, timeout=180):
    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json", "MCP-Protocol-Version": "2025-03-26"}
    if SESSION["id"]:
        headers["Mcp-Session-Id"] = SESSION["id"]
    req = urllib.request.Request(BASE + "/mcp", data=data, method="POST", headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        sid = r.headers.get("Mcp-Session-Id")
        if sid:
            SESSION["id"] = sid
        return r.status, json.loads(r.read().decode("utf-8"))


def call(cmds, expect, **args):
    _, resp = post({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                    "params": {"name": "mc_command", "arguments": {"commands": cmds, **args}}})
    r = resp["result"]["structuredContent"]["results"][0]
    head = r["output"].splitlines()[:8]
    tail = f"\n... (+{r['lines'] - len(head)} 行)" if r["lines"] > len(head) else ""
    print(f"\n=== {' '.join(cmds)} ===")
    print(f"期望 status={expect}  实际 status={r['status']}  lines={r['lines']} "
          f"dup={r['duplicates_dropped']} late={r['late_dropped']} messages={r['messages']}")
    if r.get("diagnosis"):
        print(f"diagnosis: {r['diagnosis']}")
    print("\n".join(head) + tail)
    return r["status"] == expect


def wait_connected(limit_s=120):
    """等客户端真正进入世界。

    MCP 服务在**客户端初始化时**就起来了，比 quickPlay 连上服务器早几秒，
    所以 `/health` 通了不代表能发指令（会回 `Error: not connected to a server`）—— 必须显式等。
    """
    waited = 0
    while waited < limit_s:
        _, resp = post({"jsonrpc": "2.0", "id": 9, "method": "tools/call",
                        "params": {"name": "mc_position", "arguments": {}}})
        text = resp["result"]["content"][0]["text"]
        if "not connected" not in text.lower():
            print(f"已进入世界（等待 {waited}s）")
            return True
        time.sleep(3)
        waited += 3
    print(f"等待 {limit_s}s 仍未进入世界")
    return False


def call_many(cmds, **args):
    """一次调用发多条指令（批量确认只弹一次），逐条打印 status。"""
    _, resp = post({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                    "params": {"name": "mc_command", "arguments": {"commands": cmds, **args}}},
                   timeout=300)
    res = resp["result"]["structuredContent"]["results"]
    print(f"\n=== 批量 {len(cmds)} 条 ===")
    for r in res:
        first = (r["output"].splitlines() or [""])[0][:46]
        print(f"{r['command']:<14} status={r['status']:<6} lines={r['lines']:>3} "
              f"dup={r['duplicates_dropped']:>2} late={r['late_dropped']} | {first}")
    return [r for r in res if r["status"] != "ok"]


def main():
    st, init = post({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                     "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                                "clientInfo": {"name": "co_smoke", "version": "1"}}})
    info = init["result"]["serverInfo"]
    print(f"HTTP {st}  serverInfo.name={info['name']}  serverInfo.version={info['version']}")
    if EXPECT_VERSION:
        assert info["version"] == EXPECT_VERSION, \
            f"版本号不对: {info['version']} != {EXPECT_VERSION}"
    if not wait_connected():
        return 2

    results = [
        # 查无此人 = "占位 + 一句结论" = 正常收尾，必须 ok
        call(["/co lookup u:NoSuchPlayer_zzz t:30d"], "ok", timeout_ms=20000),
        # 真数据 + hover（绝对时间戳 / 自定义物品名）+ stop_regex 分页收口
        call([f"/co lookup u:{PLAYER} a:container t:30d"], "ok",
             timeout_ms=40000, stop_regex=PAGE),
        # 普通有输出的命令
        call(["/list"], "ok", timeout_ms=15000),
    ]
    # 翻页回归：上一条 lookup 建好结果集后连翻 6 页（页脚 stop_regex 收口）
    bad_pages = call_many([f"/co page {i}" for i in range(1, 7)],
                          timeout_ms=30000, stop_regex=PAGE)
    results.append(not bad_pages)

    ok = sum(results)
    print(f"\n==== 通过 {ok} / {len(results)} ====")
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
