# 更新日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-09-14

首个版本。

### 新增

- **`mc_command`**：以本地玩家身份执行 1..N 条指令并抓回显；支持 `quiet_ms`（静默窗口）、
  `timeout_ms`（硬超时）、`stop_regex`（命中即收口）、`strip_codes`、`filter_chat`、
  `resolve_item_ids`、`hover_hints` 等参数。
- **MCP 协议子集**：`initialize` / `notifications/initialized` / `ping` / `tools/list` / `tools/call`；
  `Mcp-Session-Id` 会话（错会话 → 404）、`MCP-Protocol-Version` 校验（→ 400）、
  `GET /mcp` → 405、`DELETE /mcp` → 204、通知 → 202、未 `initialize` 直接调用 → `-32002`。
- **捕获层**：一次命令 = 一个捕获会话；跨通道（聊天栏 / 网络包）成对去重，内容相同的重复行不会被吞；
  冷却期隔离翻页输出；占位行保护避免整页丢失。
- **单行 hover 抓取**：把相对时间（如 `9.04/d 前`）的悬浮文本作为 `  (hover) <文本>` 返回，
  其中的绝对时间戳可用于对表统计。
- **结构化结果**：每次调用返回 `structuredContent`（逐条 `status` / `lines` / 丢行计数 / `output` +
  汇总 + `hint`），并带 `queried_at`；`incomplete` 只在**一行真结果都没有**时给出。
- `scripts/` 调试与验收脚本；CI（`.github/workflows/build.yml`）。

### 变更

- `serverInfo.version` 运行时从模组元数据读取，与 `gradle.properties` 的 `mod_version` 保持一致。
- `tools/list` 只暴露 `mc_command`。

[1.0.0]: https://github.com/Laoqi2603/mcpcommand/releases/tag/v1.0.0
