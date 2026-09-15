# -*- coding: utf-8 -*-
"""发布前自检：列出"会被提交的文件"，并检查编码与私有信息。

用法（在**仓库根目录**运行）:
    python scripts/precheck_publish.py

它做三件事：
  1. 按 `.gitignore` 的规则做一次**近似**过滤（不调用 git），列出会进仓库的文件与体积；
  2. 检查每个文本文件是不是**合法 UTF-8**（混入 GBK 的文件在 GitHub 上会变乱码，
     而且很多工具（编辑器、CI、评审界面）会直接报错）；
  3. 扫一遍不该公开的东西：本机绝对路径、个人账号名、内部文档引用等。

`FORBIDDEN` 里的规则按你自己的环境补充 —— 这里是通用版本。
"""
import os
import re
import sys

SKIP_DIRS = {".git", ".gradle", "build", "run", "dist", ".idea", "__pycache__", "out", "classes",
             "libs", "docs"}   # 这些目录被 .gitignore 忽略（或本来就不该提交）
SKIP_FILES = {"交接文档.md"}     # 本地工作笔记：不发布
BINARY_EXT = {".jar", ".png", ".pyc", ".zip", ".dll", ".so"}

# 这个文件里提到"交接文档"是**故意的**（说明为什么忽略它），不算泄漏
MENTION_OK = {os.path.normpath(".gitignore")}

#: 本文件自己：FORBIDDEN 里的模式当然会出现在这里，扫自己必然自报，跳过内容检查
SELF = os.path.normpath("scripts/precheck_publish.py")

#: 不该出现在公开仓库里的东西（按需增删）
FORBIDDEN = [
    (re.compile(r"[A-Za-z]:\\Users\\[^\\\s]+"), "Windows 用户目录绝对路径"),
    (re.compile(r"lao_q"), "本机用户名"),
    (re.compile(r"Minecraft_PCL"), "本机启动器路径"),
    (re.compile(r"DayDreamer2603|testMan111|sky_water0w0|water0w0|Player\d{3}"), "个人 Minecraft 账号名"),
    (re.compile(r"交接文档"), "内部交接文档引用"),
]


def walk():
    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            if name in SKIP_FILES:
                continue
            yield os.path.normpath(os.path.join(root, name))


def main():
    if not os.path.isfile(".gitignore"):
        print("请在仓库根目录运行（找不到 .gitignore）")
        return 2

    files = sorted(walk())
    bad_encoding, bad_content = [], []
    total_bytes = 0
    for path in files:
        total_bytes += os.path.getsize(path)
        print(f"  {os.path.getsize(path):>8}  {path}")

        if os.path.splitext(path)[1].lower() in BINARY_EXT:
            continue
        try:
            text = open(path, "rb").read().decode("utf-8")
        except UnicodeDecodeError as e:
            bad_encoding.append((path, str(e)))
            continue
        if path == SELF:
            continue
        for pattern, why in FORBIDDEN:
            for m in pattern.finditer(text):
                if why == "内部交接文档引用" and path in MENTION_OK:
                    continue
                line = text[:m.start()].count("\n") + 1
                bad_content.append((path, line, why, m.group()[:60]))

    print(f"\n会进仓库的文件：{len(files)} 个，共 {total_bytes / 1024:.1f} KB")

    print("\n---- 编码检查 ----")
    if not bad_encoding:
        print("  全部为合法 UTF-8 ✅")
    for path, err in bad_encoding:
        print(f"  ❌ {path}: {err}")

    print("\n---- 私有信息检查 ----")
    if not bad_content:
        print("  未发现私有信息 ✅")
    for path, line, why, hit in bad_content:
        print(f"  ❌ {path}:{line}  [{why}] {hit}")

    return 1 if (bad_encoding or bad_content) else 0


if __name__ == "__main__":
    sys.exit(main())
