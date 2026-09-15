# MCPCommand 调试与验收脚本

这些脚本通过 **MCP(HTTP)** 打客户端里的模组（默认 `http://127.0.0.1:25888`，可用 `-Dmcpcommand.port=` 改），
不依赖任何第三方库（纯标准库）。

## 运行位置（重要）

脚本的报告/原始数据都写在**当前工作目录**，而客户端日志在客户端的 `run/` 目录下，
所以请**在客户端的工作目录里运行**：

```powershell
# 开发客户端：工作目录就是仓库的 run/
cd <仓库目录>\run
python ..\scripts\verify_all.py           # ← 路径按需调整

# 正式客户端（PCL / HMCL / 官方启动器等）：工作目录是实例目录，日志在 <实例>\logs\latest.log
cd <你的实例目录>                          # 例如 %APPDATA%\.minecraft\versions\1.21.11-Fabric
python <仓库目录>\scripts\co_v4.py
```

## 脚本清单

| 脚本 | 用途 | 典型用法 |
| --- | --- | --- |
| `verify_all.py` | **全量验收**：协议合规 + `mc_command` + 会话终止 | `python verify_all.py` |
| `mcp_probe.py` | 手工探针：`wait` / `smoke` / `cmd "<指令>"` / `rawfile <json>` | `python mcp_probe.py cmd "/list"` |
| `co_test.py` | 会话查询回归：lookup → `/co page N` 翻页 → 一致性 → 结构校验 | `python co_test.py t:1d 5 15` |
| `co_v4.py` | **大数据量验收**：任意 lookup + 全量翻页 + 截断/空页检测 + 调参建议 | `python co_v4.py "/co lookup u:xxx a:container t:30d" 3` |
| `co_evidence.py` | **丢行取证**：客户端日志 vs MCP 抓回，**按出现次数**比对 | `python co_evidence.py v4-raw-*.json <日志路径>` |
| `co_items.py` | 容器交易明细表（物品名 + 附魔分布，输出 `container-items.txt`） | `python co_items.py v4-raw-*.json` |
| `co_container.py` | 容器查询分析：翻页 + 物品统计 + hover 捕获检测 | `python co_container.py <玩家> t:30d 30` |
| `co_diff.py` | 同一查询多次结果的逐行差异 | `python co_diff.py co-raw-*.json` |
| `co_retest.py` | 对被截断的页用不同 `quiet_ms` 复测，定位所需窗口 | `python co_retest.py 116,117 250,1500` |
| `co_smoke.py` | **升级冒烟**：核对 `serverInfo.version` + 三条真机指令的 `status` 判定 | `python co_smoke.py 25888 1.0.0` |
| `co_rule_check.py` | **规则回归**（不需要客户端）：用 `v4-raw-*.json` 语料验 `incomplete` 判据 | `python co_rule_check.py` |
| `verify_jar.py` | **交付物核验**：查 jar 字节码，确认版本号与关键修复真的在包里 | `python verify_jar.py build/libs/mcpcommand-1.0.0.jar` |
| `precheck_publish.py` | **发布前自检**（在仓库根目录跑）：会提交哪些文件、是否都是合法 UTF-8、有没有混进私有信息 | `python scripts/precheck_publish.py` |

## 输出文件（都在当前工作目录，属调试产物、不要提交）

`verify-report.txt`、`co-report.txt`、`v4-report.txt`、`v4-raw-*.json`、`co-raw-*.json`、
`container-items.txt`、`retest-report.txt`、`mcp-report.txt`、`health-monitor.log`

## 这些脚本踩过的坑（改脚本前先看）

1. **输出语言不固定**：插件会输出英文 `logged in / Page 1/6`、`已登录 / 已离开`、
   `登入 / 离开`、`放入 / 取出` 等多种形态（服务端 `language:` + 客户端语言都会影响），
   **所有正则都必须中英双语 + 变体容错**；
2. **时间格式有变体**：`0.75/m ago`、`0.87分钟前`、`9.04/d 前`（单位与"前"之间有空格）；
3. **相对时间不能参与比对/哈希**：两次查询之间必然变化；并且**1 秒内的两笔不同交易可能渲染成同一串**
   （`1.38/m ago`），用原文做去重键会把它们合并 → 比对前必须归一化；
4. **客户端日志编码不确定**：Windows 中文环境常见 **GBK**（PCL 的 `-Dfile.encoding=COMPAT`），
   开发环境是 UTF-8；`▶` 这类符号在 GBK 里存不下会变成 `?`，比对时要归一化符号（见 `co_evidence.py`）；
5. **聊天增强 mod 会加前缀**：部分 mod 在消息前注入 `[HH:MM:SS]`，比对时要剥掉；
6. **`duplicates_dropped` 不是丢数据**：它是跨通道去重（每条消息正常 +1）；异常信号看 `late_dropped`
   与 `status=incomplete`（**一行真结果都没收到**、只有"正在搜索…"占位/页脚 = 结果没等到，
   需要提高 `timeout_ms`）。注意"占位 + 一句结论"（如 `玩家 "x" 未找到。`）是**正常收尾**，算 `ok`。
