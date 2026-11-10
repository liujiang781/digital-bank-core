#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批量导出 docs/uml 下所有 .puml 为 SVG（交付收尾用）。
- 使用 PlantUML 1.2025.4 内置 Smetana 布局引擎，无需安装 Graphviz。
- 自动注入 `!pragma layout smetana` 与 `skinparam defaultFontName "Microsoft YaHei"`，
  保证中文不乱码（验收要求）。
- 自动跳过模板文件（以 _ 开头或文件名含 template）。
- 支持单文件多 @startuml（如 design-class.puml 含 2-6a/2-6b），将所有生成的 SVG 一并导出。
- 输出到 docs/uml/out/，文件名与图内 @startuml 名称或源文件同名。

用法（在仓库根执行）：
  python docs/uml/render_all.py
环境变量 PLANTUML_JAR 可覆盖 jar 路径。
"""
import os
import sys
import subprocess
import tempfile
import shutil

ROOT = os.path.dirname(os.path.abspath(__file__))          # docs/uml
OUT_DIR = os.path.join(ROOT, "out")

DEFAULT_JAR = r"C:/Users/26043/.workbuddy/binaries/plantuml/plantuml-new.jar"
PLANTUML_JAR = os.environ.get("PLANTUML_JAR", DEFAULT_JAR)

FONT_LINE = 'skinparam defaultFontName "Microsoft YaHei"'
PRAGMA_LINE = "!pragma layout smetana"


def is_skippable(path: str) -> bool:
    name = os.path.basename(path)
    if name.startswith("_"):
        return True
    if "template" in name.lower():
        return True
    return False


def prepare(src: str, tmp: str) -> None:
    """读取源文件，必要时注入一次 Smetana 与 YaHei 字体指令（全局生效，作用于文件内所有图）。"""
    with open(src, encoding="utf-8") as f:
        lines = f.read().splitlines()
    has_pragma = any(PRAGMA_LINE in ln for ln in lines)
    has_font = any("defaultFontName" in ln for ln in lines)
    out = []
    injected = False
    for ln in lines:
        out.append(ln)
        if (not injected) and ln.strip().startswith("@startuml"):
            if not has_pragma:
                out.append(PRAGMA_LINE)
            if not has_font:
                out.append(FONT_LINE)
            injected = True
    with open(tmp, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


def main() -> int:
    if not os.path.isfile(PLANTUML_JAR):
        print(f"[ERROR] 找不到 PlantUML jar：{PLANTUML_JAR}")
        print("请用 PLANTUML_JAR 环境变量指定，或下载 plantuml-1.2025.4.jar。")
        return 2
    os.makedirs(OUT_DIR, exist_ok=True)

    puml_files = []
    for dirpath, _, filenames in os.walk(ROOT):
        for fn in filenames:
            if fn.lower().endswith(".puml"):
                full = os.path.join(dirpath, fn)
                if is_skippable(full):
                    print(f"[SKIP] 模板/占位文件：{os.path.relpath(full, ROOT)}")
                    continue
                puml_files.append(full)

    if not puml_files:
        print("[WARN] 未发现可渲染的 .puml 文件。")
        return 0

    ok, fail = 0, 0
    for src in sorted(puml_files):
        rel = os.path.relpath(src, ROOT)
        base = os.path.splitext(os.path.basename(src))[0]
        try:
            tmpdir = tempfile.mkdtemp(prefix="puml_")
            tmp = os.path.join(tmpdir, base + ".puml")
            prepare(src, tmp)
            r = subprocess.run(
                ["java", "-jar", PLANTUML_JAR, "-tsvg", "-charset", "UTF-8",
                 "-o", tmpdir, tmp],
                capture_output=True, text=True, timeout=180,
            )
            svg_files = sorted(
                os.path.join(tmpdir, f) for f in os.listdir(tmpdir)
                if f.lower().endswith(".svg")
            )
            if r.returncode != 0 and not svg_files:
                print(f"[FAIL] {rel}\n{r.stderr.strip()[-400:]}")
                fail += 1
                continue
            if not svg_files:
                print(f"[FAIL] {rel}：未生成任何 SVG（rc={r.returncode}）")
                fail += 1
                continue
            # 校验是否出现 Graphviz 缺失报错
            bad = False
            for svgf in svg_files:
                if "Cannot find Graphviz" in open(svgf, encoding="utf-8", errors="ignore").read():
                    print(f"[FAIL] {rel}：含 Graphviz 缺失提示（应已被 Smetana 替代）")
                    bad = True
                    break
            if bad:
                fail += 1
                continue
            for svgf in svg_files:
                shutil.move(svgf, os.path.join(OUT_DIR, os.path.basename(svgf)))
            print(f"[OK]   {rel} -> out/（{len(svg_files)} 张 SVG）")
            ok += 1
        except Exception as e:  # noqa
            print(f"[FAIL] {rel}：{e}")
            fail += 1

    print(f"\n完成：成功 {ok} 个源文件，失败 {fail} 个。输出目录：{OUT_DIR}")
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
