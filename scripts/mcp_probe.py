"""MCPCommand MCP 调试探针

用法:
    python mcp_probe.py wait                 # 轮询 /health 直到服务起来
    python mcp_probe.py smoke                # initialize + tools/list + 占位工具
    python mcp_probe.py cmd "/list"          # 通过 mc_command 真机执行一条指令
    python mcp_probe.py raw '{"jsonrpc":...}'# 原样发一个请求

所有结果同时写到同目录的 mcp-report.txt (UTF-8)，便于用编辑器查看。
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:25888"
REPORT = "mcp-report.txt"
_lines = []


def log(msg=""):
    _lines.append(msg)
    print(msg.encode("ascii", "replace").decode("ascii"))


def flush():
    with open(REPORT, "a", encoding="utf-8") as f:
        f.write("\n".join(_lines) + "\n")


def http(method, path, payload=None, timeout=30):
    url = BASE + path
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read().decode("utf-8")


def rpc(method, params=None, rid=1, timeout=30):
    payload = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        payload["params"] = params
    status, body = http("POST", "/mcp", payload, timeout=timeout)
    return status, json.loads(body)


_INITIALIZED = False


def ensure_initialized():
    """规范的 MCP 客户端要先 initialize（模组会校验）"""
    global _INITIALIZED
    if _INITIALIZED:
        return
    rpc("initialize", {
        "protocolVersion": "2025-03-26",
        "capabilities": {},
        "clientInfo": {"name": "mcpcommand-debug-probe", "version": "0.1"},
    })
    try:
        http("POST", "/mcp", {"jsonrpc": "2.0", "method": "notifications/initialized"})
    except Exception:
        pass
    _INITIALIZED = True


def show(title, status, obj):
    log("")
    log("=" * 72)
    log(f"{title}   [HTTP {status}]")
    log("-" * 72)
    log(json.dumps(obj, ensure_ascii=False, indent=2))


def cmd_wait():
    log("轮询 http://127.0.0.1:25888/health ...")
    deadline = time.time() + 240
    while time.time() < deadline:
        try:
            status, body = http("GET", "/health", timeout=5)
            log(f"  [HTTP {status}] {body}")
            if status == 200:
                log("MCP 服务已就绪")
                return 0
        except urllib.error.URLError as e:
            log(f"  等待中... ({e.reason})")
        except Exception as e:  # noqa: BLE001
            log(f"  等待中... ({e})")
        time.sleep(5)
    log("超时：MCP 服务未就绪")
    return 1


def cmd_smoke():
    ensure_initialized()
    st, health = http("GET", "/health")
    log(f"/health -> [HTTP {st}] {health}")
    show("initialize", *rpc("initialize", {
        "protocolVersion": "2025-03-26",
        "capabilities": {},
        "clientInfo": {"name": "mcpcommand-debug-probe", "version": "0.1"},
    }))
    st, res = rpc("tools/list", {})
    names = [t["name"] for t in res.get("result", {}).get("tools", [])]
    log("")
    log(f"tools/list -> [HTTP {st}] 工具数={len(names)}: {names}")
    show("tools/call mc_players（占位工具）", *rpc("tools/call", {
        "name": "mc_players", "arguments": {}}))
    return 0


def cmd_cmd(command):
    ensure_initialized()
    show(f"tools/call mc_command  command={command!r}",
         *rpc("tools/call", {"name": "mc_command", "arguments": {"command": command}}, timeout=60))
    return 0


def main():
    if len(sys.argv) < 2:
        log(__doc__)
        return 2
    action = sys.argv[1]
    try:
        if action == "wait":
            return cmd_wait()
        if action == "smoke":
            return cmd_smoke()
        if action == "cmd":
            return cmd_cmd(sys.argv[2])
        if action == "raw":
            ensure_initialized()
            status, body = http("POST", "/mcp", json.loads(sys.argv[2]), timeout=60)
            log(f"[HTTP {status}] {body}")
            return 0
        if action == "rawfile":
            ensure_initialized()
            # 从文件读 payload —— 绕开 PowerShell 传给原生程序时吞掉双引号的问题
            with open(sys.argv[2], encoding="utf-8") as f:
                payload = json.load(f)
            log(f"请求: {json.dumps(payload, ensure_ascii=False)}")
            status, body = http("POST", "/mcp", payload, timeout=120)
            show("响应", status, json.loads(body))
            return 0
        log(f"未知动作: {action}")
        return 2
    finally:
        flush()


if __name__ == "__main__":
    sys.exit(main())
