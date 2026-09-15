"""从容器查询的原始返回里，生成「逐笔交易 + 物品名 + 附魔」明细表

用法:
    python co_items.py [co-container-raw-*.json]

输出:
    container-items.txt   逐笔明细 + 按物品汇总（含附魔分布）
"""
import json
import re
import sys
from collections import Counter, defaultdict

SECTION_RE = re.compile(r"\u00a7.")
RELTIME_RE = re.compile(r"\d+(?:\.\d+)?\s*(?:/[smhd]\s*(?:ago|前)|(?:秒|分钟|小时|天)\s*前)", re.IGNORECASE)
EVENT_RE = re.compile(
    r"^(?P<t>\S+\s*(?:ago|前))\s+(?P<sign>[+-])\s+(?P<user>\S+)\s+"
    r"(?P<verb>added|removed|took|put|withdrew|deposited|inserted|extracted|放入|取出|添加|移除|存入|拿出)\s+"
    r"x(?P<count>\d+)\s+(?P<item>.+?)[.。]?$", re.IGNORECASE)
COORD_RE = re.compile(r"^\s*\^\s*\(x(-?\d+)/y(-?\d+)/z(-?\d+)/(\S+?)\)")
DETAIL_RE = re.compile(r"^\s{2,}\[(?P<name>.+?)\]\s*(?:\{\s*(?P<ench>.+?)\s*\})?\s*$")


def clean(t):
    return SECTION_RE.sub("", t)


def main():
    path = sys.argv[1]
    raw = json.load(open(path, encoding="utf-8"))

    out = []
    txns = []
    seen_lookup = False          # 末尾有一致性复查询，必须跳过，否则同一笔交易会被重复统计
    for rec in raw:
        cmd = rec["command"]
        if "container" not in cmd and "page" not in cmd:
            continue
        if "container" in cmd:
            if seen_lookup:
                continue
            seen_lookup = True
        body = [clean(l) for l in rec["text"].splitlines()]
        cur = None
        for line in body:
            s = line.strip()
            if not s:
                continue
            dm = DETAIL_RE.match(line)
            if dm and cur is not None:
                cur["display"] = dm.group("name")
                cur["ench"] = (dm.group("ench") or "").strip()
                continue
            em = EVENT_RE.match(s)
            if em:
                cur = {
                    "t": em.group("t"), "sign": em.group("sign"), "user": em.group("user"),
                    "count": int(em.group("count")), "item": em.group("item").strip(),
                    "display": None, "ench": None, "coord": None, "src": cmd,
                }
                txns.append(cur)
                continue
            cm = COORD_RE.match(s)
            if cm and cur is not None:
                cur["coord"] = f"({cm.group(1)},{cm.group(2)},{cm.group(3)},{cm.group(4)})"
                cur = None

    out.append("=" * 100)
    out.append("容器交易明细（经 MCP mc_command 抓取）")
    out.append(f"数据源: {path}    交易笔数: {len(txns)}")
    out.append("=" * 100)
    out.append("")
    out.append(f"{'时间':>10} {'':2} {'玩家':<12} {'数量':>4}  {'物品ID':<20} {'物品名/附魔（来自 hover tooltip）':<44} 坐标")
    out.append("-" * 100)
    for t in txns:
        disp = t["display"] or ""
        ench = f" {{{t['ench']}}}" if t["ench"] else ""
        detail = (disp + ench) if disp else "（无 tooltip）"
        out.append(f"{t['t']:>10} {t['sign']:2} {t['user']:<12} x{t['count']:<3} {t['item']:<20} {detail:<44} {t['coord'] or '—'}")

    # 按物品汇总
    out.append("")
    out.append("=" * 100)
    out.append("按物品汇总（含附魔分布）")
    out.append("=" * 100)
    agg = defaultdict(lambda: {"add": 0, "rem": 0, "add_n": 0, "rem_n": 0, "ench": Counter(), "named": set()})
    for t in txns:
        a = agg[t["item"]]
        if t["sign"] == "+":
            a["add"] += 1
            a["add_n"] += t["count"]
        else:
            a["rem"] += 1
            a["rem_n"] += t["count"]
        if t["ench"]:
            a["ench"][t["ench"]] += 1
        if t["display"]:
            a["named"].add(t["display"])
    for item, a in sorted(agg.items(), key=lambda kv: -(kv[1]["add"] + kv[1]["rem"])):
        out.append(f"\n■ {item}")
        out.append(f"   放入 {a['add']} 次 / {a['add_n']} 个      取出 {a['rem']} 次 / {a['rem_n']} 个")
        if a["named"]:
            out.append(f"   物品名(tooltip): {', '.join(sorted(a['named']))}")
        if a["ench"]:
            out.append("   附魔分布:")
            for e, n in a["ench"].most_common():
                out.append(f"     {n:>3} 笔  {e}")
        else:
            out.append("   附魔: （这些交易没有附带 tooltip，通常是普通无附魔物品）")

    text = "\n".join(out) + "\n"
    open("container-items.txt", "w", encoding="utf-8").write(text)
    print(text[:3000])
    print(f"\n... 完整明细见 container-items.txt（共 {len(txns)} 笔）")


if __name__ == "__main__":
    main()
