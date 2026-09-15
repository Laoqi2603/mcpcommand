"""MCPCommand 全量运行时验收：MCP 协议合规 + 六个工具

用法: python verify_all.py
输出: run/verify-report.txt（UTF-8）
"""
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:25888"
REPORT = "verify-report.txt"
_lines = []
_session = None


def log(m=""):
    _lines.append(m)
    print(m.encode("ascii", "replace").decode("ascii"))


def call(method, path="/mcp", payload=None, headers=None, timeout=60):
    hdrs = {"Content-Type": "application/json"}
    if headers:
        hdrs.update(headers)
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")


def rpc(method, params=None, rid=1, extra_headers=None, timeout=60):
    payload = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        payload["params"] = params
    return call("POST", payload=payload, headers=extra_headers, timeout=timeout)


def tool(name, arguments):
    st, hdrs, body = rpc("tools/call", {"name": name, "arguments": arguments}, timeout=120)
    try:
        obj = json.loads(body)
        text = obj["result"]["content"][0]["text"]
        struct = obj["result"].get("structuredContent")
        is_err = obj["result"].get("isError", False)
        return st, text, struct, is_err
    except Exception:
        return st, body, None, True


def hget(headers, name):
    """JDK 的 HttpServer 会把头名规范成 Mcp-session-id 这种形式，所以按大小写不敏感查"""
    for k, v in headers.items():
        if k.lower() == name.lower():
            return v
    return None


def main():
    global _session
    ok = fail = 0

    def check(label, cond, detail=""):
        nonlocal ok, fail
        if cond:
            ok += 1
            log(f"  [PASS] {label}" + (f"  {detail}" if detail else ""))
        else:
            fail += 1
            log(f"  [FAIL] {label}  {detail}")

    log("=" * 78)
    log(f"MCPCommand 运行时验收   {time.strftime('%Y-%m-%d %H:%M:%S')}")
    log("=" * 78)

    log("\n### A. MCP 协议合规")

    # 未 initialize 直接 tools/list → -32002
    st, _, body = rpc("tools/list", {})
    check("未 initialize 直接 tools/list 被拒 (-32002)", st == 200 and "-32002" in body, body[:80])

    # initialize → 必须带 Mcp-Session-Id
    st, hdrs, body = rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                                        "clientInfo": {"name": "verify_all", "version": "1.0"}})
    _session = hget(hdrs, "Mcp-Session-Id")
    check("initialize 返回 Mcp-Session-Id", bool(_session), str(_session))
    check("initialize 返回 serverInfo=MCPCommand", "MCPCommand" in body)

    # 通知 → 202
    st, _, _ = call("POST", payload={"jsonrpc": "2.0", "method": "notifications/initialized"})
    check("通知请求返回 202", st == 202, f"HTTP {st}")

    # 带正确会话 → 200
    st, _, body = rpc("tools/list", {}, extra_headers={"Mcp-Session-Id": _session or ""})
    check("带正确会话 tools/list 正常", st == 200 and "mc_command" in body)

    # 带错误会话 → 404
    st, _, body = rpc("tools/list", {}, extra_headers={"Mcp-Session-Id": "bogus-session"})
    check("错误会话 id 返回 404", st == 404, f"HTTP {st} {body[:60]}")

    # 协议版本头不匹配 → 400
    st, _, body = rpc("tools/list", {}, extra_headers={"MCP-Protocol-Version": "1999-01-01"})
    check("不支持的协议版本返回 400", st == 400, f"HTTP {st}")

    # GET /mcp → 405 + Allow
    st, hdrs, _ = call("GET")
    check("GET /mcp 返回 405", st == 405, f"HTTP {st}")
    check("405 带 Allow 头", hget(hdrs, "Allow") is not None, str(hget(hdrs, "Allow")))

    # 非法 JSON → -32700 且不泄漏内部异常
    st, _, body = call("POST", payload=None, headers={"Content-Type": "application/json"})
    req = urllib.request.Request(BASE + "/mcp", data=b"this is not json",
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            body = r.read().decode()
    except urllib.error.HTTPError as e:
        body = e.read().decode()
    check("非法 JSON → -32700 且不含 Gson 细节",
          "-32700" in body and "gson" not in body.lower() and "MalformedJson" not in body, body[:90])

    log("\n### B. 工具")

    st, _, body = rpc("tools/list", {}, extra_headers={"Mcp-Session-Id": _session or ""})
    listed = [t["name"] for t in json.loads(body)["result"]["tools"]] if st == 200 else []
    log(f"  tools/list → {listed}")
    check("tools/list 只有 mc_command", listed == ["mc_command"], str(listed))

    # 未在 tools/list 里暴露的工具，按名字调用也应当被拒
    for hidden in ("mc_players", "mc_position", "mc_world_info", "mc_inventory", "mc_chat"):
        st, text, struct, err = tool(hidden, {})
        check(f"{hidden} 不在列表中且调用被拒", st == 200 and err and "Unknown tool" in text, text[:60])

    st, text, struct, err = tool("mc_command", {"command": "/list", "strip_codes": True})
    log(f"\n  mc_command [HTTP {st}] err={err}\n{text}")
    check("mc_command 正常且带结构化结果", st == 200 and struct and "summary" in struct,
          json.dumps(struct.get("summary") if struct else None))

    st, text, struct, err = tool("mc_command", {"command": "/definitely_not_a_command_xyz"})
    hint = (struct or {}).get("hint")
    log(f"\n  mc_command(未接受指令) hint={hint}")
    check("服务端拒绝指令时给出 hint", bool(hint), str(hint)[:70])

    log("\n### C. 会话终止（DELETE）")
    st, _, _ = call("DELETE")
    check("DELETE /mcp 返回 204", st == 204, f"HTTP {st}")
    st, _, body = rpc("tools/list", {})
    check("DELETE 之后必须重新 initialize", "-32002" in body, body[:70])

    log("\n" + "=" * 78)
    log(f"结果: {ok} 通过 / {fail} 失败")
    log("=" * 78)

    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(_lines) + "\n")
    log(f"报告: {REPORT}")


if __name__ == "__main__":
    main()
