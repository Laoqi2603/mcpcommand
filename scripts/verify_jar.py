"""交付物核验：确认「要发出去的那个 jar」里，版本号和关键修复**真的都在**。

用法:
    python verify_jar.py [jar路径]
    不给路径时自动找：`dist/*.jar` → `build/libs/*.jar`（多个候选取文件名里版本号最大的）

核验点（都是"源码改了但打出来的包还是旧的"最容易翻车的地方）:
  1. `fabric.mod.json` 的 `id` / `version`；
  2. `McpProtocolHandler.class` 含 `getModContainer`（版本运行时取自元数据）且**不含硬编码 `1.0.0`**；
  3. `OutputCapture$Session.class` 含 `shouldExtendQuiet` / `isNonDataLine`；
  4. `OutputCapture.class` 里的页脚正则是**双序版**（覆盖「第 1/6 页」），旧写法已消失。

注意：`IN_PROGRESS_MARKERS` / `PAGE_FOOTER` 是**外层类** `OutputCapture` 的静态字段，
`shouldExtendQuiet` / `isNonDataLine` 在**内层** `Session` 里 —— 两处都要查，查错地方会误判成"修复没进去"。

端口 25888 是 int 常量、不以字符串形式出现在 class 里，所以这里不查端口 ——
以实机 `GET /health` 通在 25888 为准。
"""
import glob
import json
import os
import re
import sys
import zipfile


def pick_jar():
    """按 dist/ → build/libs/ 的顺序找候选 jar，多个时取文件名里版本号最大的。"""
    for pattern in (os.path.join("dist", "*.jar"), os.path.join("build", "libs", "*.jar")):
        candidates = [p for p in glob.glob(pattern)
                      if not p.endswith("-sources.jar") and "archive" not in p]
        if candidates:
            def version_key(path):
                nums = re.findall(r"\d+", os.path.basename(path))
                return [int(n) for n in nums] or [0]
            return max(candidates, key=version_key)
    return None


def strings(blob):
    return [m.group().decode("utf-8", "replace") for m in re.finditer(rb"[\x20-\x7e]{4,}", blob)]


def main():
    jar = sys.argv[1] if len(sys.argv) > 1 else pick_jar()
    if not jar or not os.path.isfile(jar):
        print("找不到要核验的 jar；请显式给出路径，例如：")
        print("    python verify_jar.py build/libs/mcpcommand-1.0.0.jar")
        return 2
    z = zipfile.ZipFile(jar)
    names = z.namelist()

    def entry(suffix):
        hit = [n for n in names if n.endswith(suffix)]
        if not hit:
            raise SystemExit(f"jar 里找不到 {suffix}")
        return hit[0]

    meta = json.loads(z.read("fabric.mod.json"))
    print(f"jar        : {jar}")
    print(f"mod        : id={meta['id']}  version={meta['version']}  "
          f"（加载器依赖: {meta.get('depends', {})}）")

    proto = z.read(entry("mcp/McpProtocolHandler.class"))
    print(f"\n{entry('mcp/McpProtocolHandler.class')}")
    print("  版本运行时取自元数据 getModContainer:", b"getModContainer" in proto)
    print("  不再硬编码 '1.0.0'                  :", b"1.0.0" not in proto)

    outer = z.read(entry("capture/OutputCapture.class"))
    inner = z.read(entry("OutputCapture$Session.class"))
    footer = [s for s in strings(outer) if "page" in s.lower()]
    print(f"\n{entry('capture/OutputCapture.class')}")
    print("  页脚正则常量            :", footer)
    print("  覆盖中文语序「第 1/6 页」:", any("|\\d+\\s*/\\s*\\d+\\s*" in s for s in footer))
    print("  旧写法「(page|页)\\s*\\d+」已消失:", not any("(page|页)" in s for s in footer))

    is_ = strings(inner)
    print(f"\n{entry('OutputCapture$Session.class')}")
    print("  含 shouldExtendQuiet / isNonDataLine:",
          "shouldExtendQuiet" in is_, "/", "isNonDataLine" in is_)
    return 0


if __name__ == "__main__":
    sys.exit(main())
