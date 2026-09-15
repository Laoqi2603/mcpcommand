# MCPCommand

<!-- 仓库转为公开后再启用这两个动态徽章（私有仓库下 shields.io / Actions 徽章取不到数据，会显示裂图）
[![CI](https://github.com/Laoqi2603/mcpcommand/actions/workflows/build.yml/badge.svg)](https://github.com/Laoqi2603/mcpcommand/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Laoqi2603/mcpcommand)](https://github.com/Laoqi2603/mcpcommand/releases)
-->
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-62b47a)
![Fabric](https://img.shields.io/badge/Fabric_Loader-0.18.4%2B-dbd0b4)

把**当前 Minecraft 客户端**接入 AI 助手的 Fabric 模组：客户端进程内起一个本地 **MCP 服务**，
AI 助手通过它**以本地玩家身份在聊天栏执行指令**，并把聊天回显读回去。

```
AI 助手 ── MCP over HTTP ──▶ 客户端(本模组) ── 聊天栏执行指令 ──▶ 服务器
                                    ▲                              │
                                    └──────── 抓取聊天回显 ◀────────┘
```

**服务端不需要装任何东西** —— 你能手动敲的指令，AI 就能敲。

## 功能

工具：**`mc_command`** —— 执行 1..N 条指令并把回显抓回来。

- **批量执行**：一次调用跑多条指令，顺序执行、汇总返回（只弹一次确认）；
- **收口可控**：静默窗口 `quiet_ms`、硬超时 `timeout_ms`、命中即收口 `stop_regex`；
- **抓得准**：一次指令 = 一个捕获会话，跨通道（聊天栏 / 网络包）成对去重 —— 重复坐标行、
  同一秒内的两笔同文本事件都不会被吞；翻页也不会串位；
- **连悬浮文本一起抓**：相对时间（`9.04/d 前`）的悬浮提示里有绝对时间戳
  （`2026-09-14 19:31:12 CST`），物品的悬浮提示里有自定义名，坐标行还带出可执行的传送指令 ——
  都会作为 `  (hover) <文本>` 一并返回；
- **结果可判断**：返回结构化结果（逐条 `status` / 行数 / 丢行计数 / `output` + 汇总），
  `ok` / `timeout` / `incomplete` / `error` 语义明确 —— 该重取的时候会告诉你重取；
- **文本处理**：`strip_codes` 剥离 `§` 颜色码、`filter_chat` 过滤玩家聊天、
  `resolve_item_ids` 把物品 ID 换成本地化名称。

## 连接 AI 助手

**前置**：Minecraft **1.21.11** + Fabric Loader **≥ 0.18.4** + [Fabric API](https://modrinth.com/mod/fabric-api)，
把 [Releases](https://github.com/Laoqi2603/mcpcommand/releases) 里的 jar 放进 `<实例目录>/mods/`（**仅客户端**）。

1. 启动客户端并**进入一个世界或服务器**（标题界面下无法执行指令）；
2. 服务默认监听 `http://127.0.0.1:25888`，把 AI 客户端指向 **`http://127.0.0.1:25888/mcp`** 即可；
3. 确认服务活着：

```bash
curl http://127.0.0.1:25888/health
# {"status":"ok","server_available":false}
```

`server_available` 只表示「**集成服务器**（单人 / LAN 世界）是否存在」；连专用服务器时它一直是 `false`，
但指令照常可用。

手工调用（任意 MCP 客户端等价于此）：

```bash
# 1) 初始化，记下响应头里的 Mcp-Session-Id
curl -i -X POST http://127.0.0.1:25888/mcp \
  -H 'Content-Type: application/json' -H 'MCP-Protocol-Version: 2025-03-26' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'

# 2) 执行指令（数组形式，一次多条）
curl -s -X POST http://127.0.0.1:25888/mcp \
  -H 'Content-Type: application/json' -H 'MCP-Protocol-Version: 2025-03-26' \
  -H 'Mcp-Session-Id: <上一步拿到的 id>' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"mc_command","arguments":{"commands":["/list","/time set day"]}}}'
```

> 同一个 mod id **不能同时放两个版本**，升级前先删掉旧 jar。

## 许可

[MIT](LICENSE)。与 Minecraft 相关的一切权利归 Mojang / Microsoft 所有；本模组不打包任何 Minecraft 资源文件。
