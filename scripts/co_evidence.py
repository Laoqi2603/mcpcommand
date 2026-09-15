"""取证：把「客户端日志里实际收到的聊天消息」与「MCP 抓回来的文本」逐行对比

用途：判断某行是服务端没发，还是被模组捕获层吞了

用法:
    python co_evidence.py [co-raw-*.json] [latest.log路径]
"""
import json
import re
import sys

SECTION_RE = re.compile(r"\u00a7.")
CHAT_RE = re.compile(r"\[CHAT\]\s?(.*)$")
# 有些聊天增强 mod 会在消息前注入 [HH:MM:SS] 前缀，比对时要剥掉
TS_PREFIX_RE = re.compile(r"^\[\d{1,2}:\d{2}:\d{2}\]\s*")
RELTIME_RE = re.compile(r"\d+(?:\.\d+)?\s*(?:/[smhd]\s*(?:ago|前)|(?:秒|分钟|小时|天)\s*前)", re.IGNORECASE)
# 模组从 hover tooltip 追加的富文本行（清洗后形如 `[Diamond Shovel] { ... }`）
HOVER_LINE_RE = re.compile(r"^\[.+\](\s*\{.*\})?$")


def read_log(path):
    """客户端日志编码不定（Windows 中文环境常见 GBK，dev 环境是 UTF-8）——
    按 § 字符能否正确解出，在 utf-8 / gbk 之间选一个。"""
    data = open(path, "rb").read()
    best, best_score = None, -1
    for enc in ("utf-8", "gbk", "cp936", "latin-1"):
        try:
            text = data.decode(enc, errors="replace")
        except Exception:
            continue
        score = text.count("\u00a7") + text.count("查询结果") + text.count("页")
        if score > best_score:
            best, best_score = text, score
    return best or ""


def clean(t):
    return SECTION_RE.sub("", TS_PREFIX_RE.sub("", t)).strip()


def main():
    raw_path = sys.argv[1]
    log_path = sys.argv[2] if len(sys.argv) > 2 else r"logs/latest.log"

    raw = json.load(open(raw_path, encoding="utf-8"))
    lookups = [r for r in raw if "lookup" in r["command"]]
    if not lookups:
        print("原始文件里没有 lookup 调用")
        return
    last = lookups[-1]
    captured = [clean(l) for l in last["text"].splitlines() if l.strip()]

    log_lines = read_log(log_path).splitlines()
    chats = []
    for l in log_lines:
        m = CHAT_RE.search(l)
        if m:
            chats.append(clean(m.group(1)))

    # 只保留最后一条 lookup 附近的聊天（用结果表头做锚点）
    anchor = None
    for i in range(len(chats) - 1, -1, -1):
        if "Lookup Results" in chats[i]:
            anchor = i
            break
    window = chats[max(0, anchor - 30):] if anchor is not None else chats[-60:]

    print("=" * 78)
    print(f"取证对比：指令 {last['command']}")
    print("=" * 78)
    print(f"\n【A】客户端日志里实际收到的消息（{len(window)} 条，最近一批）")
    for i, c in enumerate(window):
        print(f"  A{i:02d}  {c}")

    print(f"\n【B】MCP 抓回来的文本（{len(captured)} 行）")
    for i, c in enumerate(captured):
        print(f"  B{i:02d}  {c}")

    print("\n" + "=" * 78)
    print("按出现次数比对（关键：同一条消息重复出现时，存在性比对会漏报）")
    print("=" * 78)

    # 取 A 里最后一批完整消息（从 "Lookup searching / 正在搜索" 到页脚 "Page x/y / 第 x/y 页"）
    start = None
    for i in range(len(window) - 1, -1, -1):
        if "Lookup searching" in window[i] or "正在搜索" in window[i]:
            start = i
            break
    group = []
    if start is not None:
        for c in window[start:]:
            group.append(c)
            if re.match(r"(?:Page|第)\s*\d+\s*/\s*\d+", RELTIME_RE.sub("", c)):
                break
    else:
        group = window[-12:]

    def counts(lines):
        d = {}
        for c in lines:
            k = RELTIME_RE.sub("T ago", c)
            # 符号归一化：GBK 日志里 ▶ 之类的符号存不下来（会变成 ?），统一成占位符再比
            k = re.sub(r"[^\x20-\x7e\u4e00-\u9fff]", "?", k)
            d[k] = d.get(k, 0) + 1
        return d

    ca, cb = counts(group), counts([RELTIME_RE.sub("T ago", c) for c in captured])
    print(f"\nA（客户端日志，本批 {len(group)} 条） vs B（MCP 抓回，{len(captured)} 行）\n")
    print(f"  {'次数A':>5} {'次数B':>5}  行内容")
    dropped = 0
    hover_extra = 0
    for k in sorted(set(ca) | set(cb), key=lambda x: (-ca.get(x, 0), x)):
        na, nb = ca.get(k, 0), cb.get(k, 0)
        flag = ""
        if na != nb:
            if na == 0 and HOVER_LINE_RE.match(k):
                # 模组自己从 hover tooltip 追加的富文本：客户端日志里本来就没有，不算异常
                flag = "   ← 模组追加的 hover 富文本（预期）"
                hover_extra += nb - na
            else:
                flag = "   ❌ 丢了 %d 条" % (na - nb) if na > nb else "   ⚠️ 多出 %d 条" % (nb - na)
                dropped += max(0, na - nb)
        print(f"  {na:>5} {nb:>5}  {k[:80]}{flag}")

    print(f"\n>>> 结论：客户端日志 {sum(ca.values())} 条 / MCP 抓回 {sum(cb.values())} 行；"
          f"丢失 {dropped} 条；另有 {hover_extra} 行是模组追加的 hover 富文本（预期，不在日志里）")
    if dropped:
        print("    ⚠️ 出现丢行：同一捕获窗口内『内容完全相同』的消息被去重吞掉 —— 即真实重复行被去重吞掉，")
        print("       请把这一窗口的原始返回留档（run/co-raw-*.json）以便定位。")
    else:
        print("    ✅ 本窗口无丢行（日志里收到的每一条都能在 MCP 返回里按次数对上）")


if __name__ == "__main__":
    main()
