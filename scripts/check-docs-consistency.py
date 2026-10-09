#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文档一致性门禁（T-GATE）：把「文档写 X、实况 Y」的漂移变成常驻断言。

## 为什么需要它

本仓反复出现文档漂移（CLAUDE.md 自记「此前写 26 个、实测 65 个」教训）。
2026-10-09 审查的 63 条问题里绝大多数是这类 —— 人肉对账不管用，必须机器钉住。
本脚本是这批修复的**验收标准**：先落地（此时必然红），修复完成后转绿。

## 覆盖的检查

1. 后端测试规模：CLAUDE.md / docs/testing.md 声明的「测试文件数 / *Test.java 类数」
   == 实测（扫 ZXYZdatabaseBack/**/src/test/java/**/*.java，排除 target/）；
   docs/testing.md 的分项加总表另核「分项之和 == 声明总和」。
2. 前端测试文件数：CLAUDE.md 声明 == 实测 ZXYZdatabaseFront/src/**/*.spec.js。
3. 路径引用有效性：CLAUDE.md + docs/**/*.md 里的 ISSUE|docs|scripts|.github/workflows|
   nacos-config|deploy 引用目标必须存在；例外：所在行或前 2 行含「已归档」/「oldmd」。
   ⚠️ ISSUE/ 前缀引用按目录存在性条件校验（见检查 7 的说明）：ISSUE/ 被 .gitignore，
   CI runner 上必然缺席 ⇒ 缺席时跳过 ISSUE/ 引用并计入提示，仓库内路径
   （docs|scripts|deploy|nacos-config|.github/workflows）仍严格校验。
4. Redis 库号兜底处数单一真源：docs/redis-session-layout.md 声明数 ==
   docker-compose.yml 非注释真键数。
5. 健康巡检覆盖完整性：scripts/health-check.sh 的 services 数组必须覆盖 compose 中
   所有定义了 healthcheck: 的服务的 container_name（防「漏 admin-service 假绿灯」复发）。
6. 危险指令必须有警示：ISSUE/**/*.md 中 git push / git add -A / sed -i 作用于 .env /
   git checkout <ref> -- 的行，上下 3 行内必须有警示词（⛔/需授权/会部署/不可逆/
   勿自动执行/需用户确认）—— 防「照抄计划步骤就部署到生产」。
7. CVE 豁免证据锚点：.trivyignore.yaml 引用的 ISSUE/*.md 必须存在，或明确「已归档」
   + 给出 oldmd 路径。⚠️ ISSUE/ 目录被 .gitignore（仅本机保留），CI runner 上必然缺席：
   检查 7 对 ISSUE/ 前缀锚点做**条件校验** —— ISSUE/ 目录存在时（本机）照常校验存在性；
   不存在时（CI）跳过该锚点并输出提示，绝不因「本地台账不入库」在 CI 上悬空报红。
   oldmd/ 等仓库外锚点维持既有逻辑（归档 + oldmd 路径语境放行，缺席语境不适用）。
8. 门禁自检（--self-check）：违规样本必须红并点名；合规样本必须不误报；
   含「ISSUE/ 目录不存在时检查 3/7 不误报」的 CI 形态用例（喂 None 扫描样本模拟）。

## 输出与退出码

- 通过：逐项 `✓ 检查名（实测值）`，退出码 0
- 失败：逐项 `✗ 检查名：期望 X，实测 Y（文件:行）`，**汇总全部失败项后**再退出 1
  （一次跑完列出全部 —— 批量修时不要来回跑）
- `--json`：机器可读输出（同一份数据）
- `--self-check`：只跑第 8 项自检

## 反「假绿灯」自证

每个扫描器都必须回报「扫到多少文件 / 解析到多少条目」并打印；任何计数为 0
（按当前仓库形态不可能为 0）⇒ 环境错误退出码 2，绝不静默 PASS。

依赖：仅 Python 标准库。YAML 相关检查用**缩进感知扫描 + 拒绝重复键**实现
（思路同 scripts/check-nacos-config-sync.py 的 StrictLoader：PyYAML 默认容忍
重复键，而 SnakeYAML 会因重复顶层键让服务起不来 —— 本地查不出 ≠ 没问题）。
Windows 路径与 CRLF 均已处理（读入统一 utf-8-sig + splitlines）。
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------------------
# 通用工具
# ---------------------------------------------------------------------------

def read_lines(rel_path: str) -> list[str]:
    """读文件为行列表（utf-8-sig 容忍 BOM；splitlines 同时吃掉 \r\n 与 \n）。"""
    with open(REPO / rel_path, encoding="utf-8-sig") as handle:
        return handle.read().splitlines()


def fail(failures: list[dict], check: str, expected: str, actual: str, where: str) -> None:
    failures.append({"check": check, "expected": expected, "actual": actual, "where": where})


def md_files() -> list[str]:
    """CLAUDE.md + docs/**/*.md（相对路径，/ 分隔，排序稳定）。"""
    out = ["CLAUDE.md"]
    out += sorted(
        p.replace(os.sep, "/")
        for p in glob.glob(str(REPO / "docs" / "**" / "*.md"), recursive=True)
    )
    return [p for p in out if (REPO / p).is_file()]


def issue_files() -> list[str]:
    # ISSUE/audit-docs-*/ 是审查产物（在描述危险指令，而非执行指引），
    # 不适用「危险指令须有警示」检查 —— 扫它们只会产出无意义的假红。
    return sorted(
        p.replace(os.sep, "/")
        for p in glob.glob(str(REPO / "ISSUE" / "**" / "*.md"), recursive=True)
        if "/audit-docs-" not in p.replace(os.sep, "/")
    )


# ISSUE/ 目录存在性：检查 3 / 检查 7 的「ISSUE/ 前缀锚点」条件校验的**单一真源**。
# ISSUE/ 在 .gitignore（仅本机保留），CI runner 上必然缺席 —— 缺席属预期环境形态，
# 不是文档缺陷；两个引用类检查据此跳过 ISSUE/ 锚点（仓库内路径仍严格校验）。
# 本机对 ISSUE/ 内文件的增删即真实状态，无需任何额外开关或缓存。
def issue_dir_exists() -> bool:
    return (REPO / "ISSUE").is_dir()


# CI 形态下被跳过的 ISSUE/ 锚点记录（「跳过」必须留痕，不静默）。
# 刻意**不在检查函数里直接 print**：--json 是机器可读输出，混入提示行会破坏可解析性；
# 由 run_gate 汇入 stats（issue_anchor_skips），print_report 统一以 ℹ 行输出。
ISSUE_ANCHOR_SKIPS: list[str] = []


def skip_issue_anchor(reason: str) -> None:
    """记录一个被跳过的 ISSUE/ 锚点（CI 形态：ISSUE/ 目录缺席）。"""
    ISSUE_ANCHOR_SKIPS.append(reason)


# ---------------------------------------------------------------------------
# 检查 1：后端测试规模（声明 vs 实测；分项加总表）
# ---------------------------------------------------------------------------

def scan_backend_tests() -> tuple[int, int, dict[str, int], dict[str, int]]:
    """实测后端测试文件数 / *Test.java 类数 / 每模块分项。0 文件 = 环境错误（反空扫）。"""
    files = [
        p.replace(os.sep, "/")
        for p in glob.glob(str(REPO / "ZXYZdatabaseBack" / "**" / "src" / "test" / "java" / "**" / "*.java"), recursive=True)
        if "/target/" not in p.replace(os.sep, "/")
    ]
    per_file: dict[str, int] = {}
    per_class: dict[str, int] = {}
    for p in files:
        module = p.split("/")[1]
        per_file[module] = per_file.get(module, 0) + 1
        if os.path.basename(p).endswith("Test.java"):
            per_class[module] = per_class.get(module, 0) + 1
    return len(files), sum(per_class.values()), per_file, per_class


DECLARED_COUNT_RES = [
    # CLAUDE.md:80 形态：「N 个测试类 / M 个测试源文件」
    (re.compile(r"(\d+)\s*个测试类\s*/\s*(\d+)\s*个测试源文件"), "classes_files"),
    # testing.md:17 形态：「**N 个文件**」
    (re.compile(r"\*{0,2}(\d+)\s*个文件\*{0,2}"), "files"),
    # testing.md:1314 形态：「共 N 个文件 / M 个 `*Test.java` 测试类」
    (re.compile(r"共\s*(\d+)\s*个文件\s*/\s*(\d+)\s*个\s*`?\*?Test\.java`?\s*测试类"), "files_classes"),
]

DECLARED_FRONTEND_RES = [
    (re.compile(r"(\d+)\s*个测试文件"), "files"),
    # docs/testing.md 前端分项节形态：「共 N 个文件」
    (re.compile(r"共\s*(\d+)\s*个文件"), "files"),
]


def extract_declared_counts(lines: list[str], rel: str) -> list[dict]:
    """从文档行里抽取全部「测试规模声明」，返回 [{file,line,kind,files,classes}]。"""
    out: list[dict] = []
    for idx, line in enumerate(lines, 1):
        for regex, kind in DECLARED_COUNT_RES:
            for m in regex.finditer(line):
                if kind == "classes_files":
                    out.append({"file": rel, "line": idx, "kind": "classes+files",
                                "classes": int(m.group(1)), "files": int(m.group(2)), "text": line.strip()[:120]})
                elif kind == "files_classes":
                    out.append({"file": rel, "line": idx, "kind": "classes+files",
                                "files": int(m.group(1)), "classes": int(m.group(2)), "text": line.strip()[:120]})
                elif kind == "files":
                    # 「N 个文件」只在明确谈测试规模的行里算数（该行须含「测试」字样，防误伤普通文档）
                    if "测试" in line:
                        out.append({"file": rel, "line": idx, "kind": "files",
                                    "files": int(m.group(1)), "classes": None, "text": line.strip()[:120]})
    return out


def extract_frontend_declared(lines: list[str], rel: str) -> list[dict]:
    """前端测试文件数声明：只认含「前端」或 spec.js 语境的行（防误伤普通文档）。"""
    out: list[dict] = []
    for idx, line in enumerate(lines, 1):
        if "spec.js" not in line and "前端" not in line:
            continue
        for regex, _kind in DECLARED_FRONTEND_RES:
            for m in regex.finditer(line):
                out.append({"file": rel, "line": idx, "files": int(m.group(1)),
                            "text": line.strip()[:120]})
    return out


def extract_breakdown_table(lines: list[str], rel: str) -> dict | None:
    """docs/testing.md 分项加总表：| 模块 | 文件数 | 类数 | 的各行 + 两条总和行。

    数字后允许跟任意非 `|` 注解（如 `26（含 1 抽象基类…）`）—— 这是本仓表格的
    真实形态，漏掉注解行会少算模块（实测曾因此把 163 算成 137）。
    """
    rows: dict[str, tuple[int, int]] = {}
    for idx, line in enumerate(lines, 1):
        m = re.match(r"^\|\s*`(zxyz-[a-z-]+)`\s*\|\s*(\d+)[^|]*\|\s*(\d+)[^|]*\|", line)
        if m:
            rows[m.group(1)] = (int(m.group(2)), int(m.group(3)))
    total_files = total_classes = None
    for idx, line in enumerate(lines, 1):
        m = re.search(r"总和（文件数）.*?=\s*\*{0,2}(\d+)", line)
        if m:
            total_files = (int(m.group(1)), idx)
        m = re.search(r"总和（`?\*?Test\.java`?\s*）(?:.*)?:\s*(\d+)", line)
        if m:
            total_classes = (int(m.group(1)), idx)
    if not rows and total_files is None:
        return None
    return {"file": rel, "rows": rows, "total_files": total_files, "total_classes": total_classes}


def check_backend_counts(declared: list[dict], breakdown: dict | None,
                         actual_files: int, actual_classes: int,
                         failures: list[dict]) -> None:
    """纯函数：每一条声明都必须与实测一致；分项之和必须等于声明总和。"""
    for d in declared:
        if d["kind"] == "classes+files":
            if d["files"] != actual_files:
                fail(failures, "1.后端测试规模(文件数)", str(actual_files), str(d["files"]),
                     f"{d['file']}:{d['line']}")
            if d["classes"] is not None and d["classes"] != actual_classes:
                fail(failures, "1.后端测试规模(测试类数)", str(actual_classes), str(d["classes"]),
                     f"{d['file']}:{d['line']}")
        elif d["kind"] == "files" and d["files"] != actual_files:
            fail(failures, "1.后端测试规模(文件数)", str(actual_files), str(d["files"]),
                 f"{d['file']}:{d['line']}")
    if breakdown:
        sum_files = sum(v[0] for v in breakdown["rows"].values())
        sum_classes = sum(v[1] for v in breakdown["rows"].values())
        # 语义：文档声明的总和是「期望」，脚本从分项算出的是「实测」
        if breakdown["total_files"] and breakdown["total_files"][0] != sum_files:
            fail(failures, "1.后端测试规模(分项加总:文件数)", str(breakdown["total_files"][0]),
                 str(sum_files),
                 f"{breakdown['file']}:{breakdown['total_files'][1]}")
        if breakdown["total_classes"] and breakdown["total_classes"][0] != sum_classes:
            fail(failures, "1.后端测试规模(分项加总:测试类数)", str(breakdown["total_classes"][0]),
                 str(sum_classes),
                 f"{breakdown['file']}:{breakdown['total_classes'][1]}")
        if breakdown["total_files"] and breakdown["total_files"][0] != actual_files:
            fail(failures, "1.后端测试规模(加总表 vs 实测:文件数)", str(actual_files),
                 str(breakdown["total_files"][0]),
                 f"{breakdown['file']}:{breakdown['total_files'][1]}")
        if breakdown["total_classes"] and breakdown["total_classes"][0] != actual_classes:
            fail(failures, "1.后端测试规模(加总表 vs 实测:测试类数)", str(actual_classes),
                 str(breakdown["total_classes"][0]),
                 f"{breakdown['file']}:{breakdown['total_classes'][1]}")


# ---------------------------------------------------------------------------
# 检查 2：前端测试文件数
# ---------------------------------------------------------------------------

def scan_frontend_specs() -> int:
    return len(glob.glob(str(REPO / "ZXYZdatabaseFront" / "src" / "**" / "*.spec.js"), recursive=True))


def check_frontend_count(declared: list[dict], actual: int, failures: list[dict]) -> None:
    for d in declared:
        if d["files"] != actual:
            fail(failures, "2.前端测试文件数", str(actual), str(d["files"]), f"{d['file']}:{d['line']}")


# ---------------------------------------------------------------------------
# 检查 3：路径引用有效性
# ---------------------------------------------------------------------------

REF_RES = [
    re.compile(r"(?<![\w./\\-])(ISSUE|docs|scripts|nacos-config|deploy)/[A-Za-z0-9._\-]+(?:/[A-Za-z0-9._\-]+)*"),
    re.compile(r"(?<![\w./\\-])(\.github/workflows)/[A-Za-z0-9._\-]+"),
]
PLACEHOLDER_RE = re.compile(r"(?:^|[/\\.,])xxx(?:[/.,]|$)")
WARNING_CONTEXT = ("已归档", "oldmd")
# 文件级引用免责声明：文件内出现这些「引用说明」短语且显式提到本地台账/不入库语义，
# 即视为该文件**声明过**「ISSUE/* 引用可能解析不了、仅供本地对照」⇒ 该文件的
# ISSUE/ 引用整体放行（保护决策溯源，不惩罚「自己声明了不存在」的文档）。
DOC_SCOPE_DISCLAIMER_RE = re.compile(
    r"不入库|无法解析|仅供本地|仅供本地台账对照|不在仓内")
# 自检样本仍须走行级豁免路径，防止声明被滥用到无关文件 —— 由 --self-check 覆盖。


def collect_dangling_refs(rel: str, lines: list[str]) -> list[dict]:
    """单文件内找悬空引用。归档例外：命中行或其前 2 行含「已归档」/「oldmd」⇒ 放行。"""
    out: list[dict] = []
    for idx, raw in enumerate(lines, 1):
        # 去掉 URL，防止仓库外链接被误判为本地路径引用
        line = re.sub(r"https?://\S+", "", raw)
        for regex in REF_RES:
            for m in regex.finditer(line):
                ref = m.group(0)
                if PLACEHOLDER_RE.search(ref):
                    continue  # xxx 形态是占位符写法，不是真实引用
                target = REPO / ref
                if target.exists():
                    continue
                context = "\n".join(lines[max(0, idx - 3):idx])
                if any(word in context for word in WARNING_CONTEXT):
                    continue
                out.append({"file": rel, "line": idx, "ref": ref})
    return out


def check_path_refs(docs_texts: dict[str, list[str]], failures: list[dict],
                    issue_present: bool | None = None) -> int:
    checked = 0
    # ISSUE/ 前缀引用的条件校验（与检查 7 同一设计、同一真源）：ISSUE/ 被 .gitignore，
    # CI runner 上必然缺席 —— 缺席时跳过 ISSUE/ 引用并留提示（只提示一次，不刷屏）；
    # 本机 ISSUE/ 在场时照常校验。docs|scripts|deploy 等仓库内路径任何环境都严格校验。
    # issue_present=None：探测目录本身（向后兼容）；run_gate 与自检样本显式传值保证确定。
    if issue_present is None:
        issue_present = issue_dir_exists()
    skip_hinted = False
    for rel, lines in docs_texts.items():
        # 文件级免责声明：文件头部（前 20 行）有「ISSUE 引用不入库/无法解析/仅供本地」
        # 的显式说明 ⇒ 该文件的 ISSUE/ 引用是「已声明的本地台账引用」，放行。
        # 只豁免 ISSUE/ 引用；docs|scripts|deploy 等仓库内路径仍必须真实存在。
        header = "\n".join(lines[:20])
        issue_scope_exempt = bool(
            re.search(r"ISSUE/?\*", header) and DOC_SCOPE_DISCLAIMER_RE.search(header))
        for d in collect_dangling_refs(rel, lines):
            if issue_scope_exempt and d["ref"].startswith("ISSUE/"):
                continue
            if not issue_present and d["ref"].startswith("ISSUE/"):
                if not skip_hinted:
                    skip_issue_anchor("ISSUE/ 目录不存在（CI 形态），检查 3 跳过其引用")
                    skip_hinted = True
                continue
            fail(failures, "3.路径引用有效性", "目标存在（或已标注归档）", f"悬空引用 {d['ref']}",
                 f"{d['file']}:{d['line']}")
            checked += 1
    return checked


# ---------------------------------------------------------------------------
# compose 缩进感知扫描（拒绝重复键；只依赖标准库）
# ---------------------------------------------------------------------------

class ComposeScanError(Exception):
    pass


def scan_compose(text: str) -> dict:
    """最小 compose 扫描器：
    - 顶层键 / services 下服务名 **拒绝重复**（重复键 = SnakeYAML 致命错误）；
    - 收集 {service: {container_name, has_healthcheck}}；
    - 统计非注释 `REDIS_DATABASE:` 真键数。
    只认 2 空格缩进的映射形态（本仓 compose 的实际形态）。
    """
    top_keys: list[str] = []
    services: dict[str, dict] = {}
    redis_db_keys = 0
    current_service: str | None = None
    in_services = False

    for lineno, raw in enumerate(text.splitlines(), 1):
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        stripped = line.strip()

        if indent == 0:
            m = re.match(r"^([A-Za-z0-9_.-]+):\s*$", stripped)
            if m:
                key = m.group(1)
                if key in top_keys:
                    raise ComposeScanError(f"docker-compose.yml:{lineno} 顶层重复键 '{key}'")
                top_keys.append(key)
                in_services = key == "services"
                current_service = None
            continue

        if in_services:
            if indent == 2:
                m = re.match(r"^([A-Za-z0-9_.-]+):\s*(?:#.*)?$", stripped)
                if m:
                    name = m.group(1)
                    if name in services:
                        raise ComposeScanError(f"docker-compose.yml:{lineno} services 下重复服务 '{name}'")
                    services[name] = {"container_name": None, "has_healthcheck": False}
                    current_service = name
                continue
            if indent >= 4 and current_service:
                if stripped.startswith("container_name:"):
                    services[current_service]["container_name"] = (
                        stripped.split(":", 1)[1].strip().strip('"').strip("'"))
                elif stripped.startswith("healthcheck:"):
                    services[current_service]["has_healthcheck"] = True
                elif stripped.startswith("REDIS_DATABASE:"):
                    redis_db_keys += 1
    return {"top_keys": top_keys, "services": services, "redis_db_keys": redis_db_keys}


def check_healthcheck_coverage(services: dict, script_services: list[str],
                               failures: list[dict]) -> int:
    """health-check.sh 的数组必须覆盖「每个带 healthcheck 的服务的 container_name」。"""
    required: set[str] = set()
    for name, props in services.items():
        if props["has_healthcheck"]:
            cn = props["container_name"] or f"zxyz-{name}"
            required.add(cn)
    missing = sorted(required - set(script_services))
    for m in missing:
        fail(failures, "5.健康巡检覆盖完整性", f"health-check.sh 覆盖 {m}",
             "缺失（有 healthcheck 却不在巡检数组）", "scripts/health-check.sh:services 数组")
    return len(required)


# ---------------------------------------------------------------------------
# 检查 6：危险指令必须有警示（±3 行内出现警示词）
# ---------------------------------------------------------------------------

DANGER_RES = [
    re.compile(r"\bgit push\b"),
    re.compile(r"\bgit add\s+-A\b"),
    re.compile(r"\bgit checkout\s+\S+\s+--"),
]
SED_ENV_RE = re.compile(r"\bsed\s+-i\b.*\.env")
WARNING_WORDS = ("⛔", "需授权", "需用户确认", "会部署", "不可逆", "勿自动执行",
                 "会暂存", "会触发", "会部署到生产", "先行核对", "部署到生产",
                 "危险操作警示", "执行前必读", "不得自动执行")
# 警示窗口：命令**上方 12 行内**（覆盖 fenced code block 前的警示块）或**下方 3 行内**。
UPWARD_WINDOW = 12
DOWNWARD_WINDOW = 3
# 审查产物目录：这些文件是在**描述/分析**危险指令，不是操作指引，不适用本检查。
AUDIT_DIR_MARKER = f"ISSUE{os.sep}audit-docs".replace(os.sep, "/")


def is_commandish(line: str, in_fence: bool) -> bool:
    """只对「真命令形态」要求警示：位于 fenced code block 内，或行首就是命令。"""
    if in_fence:
        return True
    stripped = line.strip()
    if stripped.startswith(("- ", "* ", "> ")):
        stripped = stripped.lstrip("-*> ").lstrip()
    return bool(re.match(r"(git|sed)\b", stripped)) and not stripped.startswith("|")


def collect_unwarned_danger(rel: str, lines: list[str]) -> list[dict]:
    out: list[dict] = []
    in_fence = False
    for idx, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("```"):
            in_fence = not in_fence
            continue
        hit = (any(r.search(line) for r in DANGER_RES) or bool(SED_ENV_RE.search(line)))
        if not hit:
            continue
        if not is_commandish(line, in_fence):
            continue  # 散文性提及（blockquote/表格里的风险描述）不是可执行指令
        lo = max(0, idx - UPWARD_WINDOW)
        hi = min(len(lines), idx + DOWNWARD_WINDOW + 1)
        if any(word in "".join(lines[lo:hi]) for word in WARNING_WORDS):
            continue
        out.append({"file": rel, "line": idx + 1, "text": line.strip()[:120]})
    return out


def check_danger_warnings(issue_texts: dict[str, list[str]], failures: list[dict]) -> int:
    hits = 0
    for rel, lines in issue_texts.items():
        for d in collect_unwarned_danger(rel, lines):
            fail(failures, "6.危险指令警示",
                 f"命令上 {UPWARD_WINDOW} 行内或下 {DOWNWARD_WINDOW} 行内有警示词（{'/'.join(WARNING_WORDS[:6])}…）",
                 f"无警示：{d['text']}", f"{d['file']}:{d['line']}")
            hits += 1
    return hits


# ---------------------------------------------------------------------------
# 检查 7：.trivyignore.yaml 的 ISSUE 证据锚点
# ---------------------------------------------------------------------------

def check_trivyignore_anchors(lines: list[str], failures: list[dict],
                              issue_present: bool | None = None) -> int:
    """检查 7：.trivyignore.yaml 的 CVE 证据锚点。

    ⚠️ ISSUE/ 前缀锚点做**条件校验**（与检查 3 同一设计、同一真源）：ISSUE/ 被
    .gitignore（仅本机保留），CI runner 上必然缺席 ——
    - issue_present=True（本机）：照常校验锚点文件存在性；
    - issue_present=False（CI）：跳过 ISSUE/ 锚点并输出提示（每个锚点一行留痕，不静默）；
    - issue_present=None：保持既有行为（探测文件本身，向后兼容既有调用/自检样本）。
    oldmd/ 等仓库外锚点维持既有逻辑：归档 + oldmd 路径语境放行，不适用缺席跳过。
    """
    if issue_present is None:
        issue_present = issue_dir_exists()
    checked = 0
    for idx, line in enumerate(lines, 1):
        for m in re.finditer(r"ISSUE/[A-Za-z0-9._\-]+\.md", line):
            checked += 1
            ref = m.group(0)
            if ref.startswith("ISSUE/") and not issue_present:
                skip_issue_anchor(f"{ref}（CI 形态，检查 7 跳过该锚点）")
                continue
            if (REPO / ref).exists():
                continue
            context = "\n".join(lines[max(0, idx - 3):idx + 2])
            if "已归档" in context and "oldmd" in context:
                continue
            fail(failures, "7.CVE 豁免证据锚点", "ISSUE 文件存在，或已归档 + oldmd 路径",
                 f"悬空锚点 {ref}", f".trivyignore.yaml:{idx}")
    return checked


# ---------------------------------------------------------------------------
# 检查 4：Redis 库号兜底处数单一真源
# ---------------------------------------------------------------------------

def extract_layout_declared_db_count(lines: list[str]) -> list[dict]:
    out: list[dict] = []
    for idx, line in enumerate(lines, 1):
        if "REDIS_DATABASE" not in line or "处" not in line:
            continue
        for m in re.finditer(r"共\s*\*{0,2}(\d+)\s*\*{0,2}\s*处", line):
            out.append({"line": idx, "count": int(m.group(1)), "text": line.strip()[:120]})
    return out


def check_redis_db_count(declared: list[dict], actual: int, failures: list[dict]) -> int:
    for d in declared:
        if d["count"] != actual:
            fail(failures, "4.Redis 库号兜底处数", str(actual), str(d["count"]),
                 f"docs/redis-session-layout.md:{d['line']}")
    return len(declared)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def run_gate(failures: list[dict]) -> dict:
    """跑全部检查（除自检），返回统计字典。failures 原地追加。"""
    stats: dict[str, int] = {}
    ISSUE_ANCHOR_SKIPS.clear()  # 每轮门禁重算跳过记录（防残留）

    # --- 自证非空 + 实测真值 ---
    actual_files, actual_classes, _, _ = scan_backend_tests()
    actual_specs = scan_frontend_specs()
    compose_text = "\n".join(read_lines("docker-compose.yml"))
    try:
        compose = scan_compose(compose_text)
    except ComposeScanError as exc:
        fail(failures, "Y. compose 重复键", "无重复键", str(exc), "docker-compose.yml")
        compose = {"top_keys": [], "services": {}, "redis_db_keys": 0}
    stats["backend_test_files"] = actual_files
    stats["backend_test_classes"] = actual_classes
    stats["frontend_spec_files"] = actual_specs
    stats["compose_services"] = len(compose["services"])
    stats["compose_redis_db_keys"] = compose["redis_db_keys"]

    if actual_files <= 0 or actual_specs <= 0 or not compose["services"]:
        fail(failures, "反空扫", "扫描计数 > 0",
             f"backend={actual_files} frontend={actual_specs} services={len(compose['services'])}",
             "scripts/check-docs-consistency.py（路径写错会在这里响亮失败）")

    docs_texts = {rel: read_lines(rel) for rel in md_files()}
    stats["docs_md_files"] = len(docs_texts)
    if not docs_texts:
        fail(failures, "反空扫", "docs 文件数 > 0", "0", "CLAUDE.md + docs/**/*.md")

    issue_texts = {rel: read_lines(rel) for rel in issue_files()}
    stats["issue_md_files"] = len(issue_texts)

    # --- 检查 1 ---
    declared_backend: list[dict] = []
    declared_backend += extract_declared_counts(read_lines("CLAUDE.md"), "CLAUDE.md")
    testing_lines = read_lines("docs/testing.md")
    declared_backend += extract_declared_counts(testing_lines, "docs/testing.md")
    breakdown = extract_breakdown_table(testing_lines, "docs/testing.md")
    stats["backend_declared_spots"] = len(declared_backend)
    if not declared_backend:
        fail(failures, "反空扫", "解析到 ≥1 条后端规模声明", "0",
             "CLAUDE.md / docs/testing.md（声明格式变了？门禁会漏检）")
    check_backend_counts(declared_backend, breakdown, actual_files, actual_classes, failures)

    # --- 检查 2 ---
    declared_fe = extract_frontend_declared(read_lines("CLAUDE.md"), "CLAUDE.md")
    declared_fe += extract_frontend_declared(read_lines("docs/testing.md"), "docs/testing.md")
    stats["frontend_declared_spots"] = len(declared_fe)
    if not declared_fe:
        fail(failures, "反空扫", "解析到 ≥1 条前端规模声明", "0", "CLAUDE.md / docs/testing.md")
    check_frontend_count(declared_fe, actual_specs, failures)

    # --- 检查 3 ---
    # ISSUE/ 前缀引用条件校验（ISSUE/50 §四门禁接线）：真源 issue_dir_exists()，
    # CI 形态（目录缺席）下跳过 ISSUE/ 引用并提示，仓库内路径仍严格校验。
    stats["path_refs_dangling"] = check_path_refs(docs_texts, failures, issue_dir_exists())

    # --- 检查 4 ---
    layout_lines = read_lines("docs/redis-session-layout.md")
    declared_db = extract_layout_declared_db_count(layout_lines)
    stats["layout_declared_spots"] = len(declared_db)
    if not declared_db:
        fail(failures, "反空扫", "解析到 ≥1 条 REDIS_DATABASE 处数声明", "0",
             "docs/redis-session-layout.md（声明格式变了？门禁会漏检）")
    check_redis_db_count(declared_db, compose["redis_db_keys"], failures)

    # --- 检查 5 ---
    hc_script_text = "\n".join(read_lines("scripts/health-check.sh"))
    m = re.search(r"services=\(([^)]*)\)", hc_script_text, re.S)
    script_services = sorted(set(re.findall(r"zxyz-[a-z0-9-]+", m.group(1)))) if m else []
    stats["healthcheck_services_required"] = check_healthcheck_coverage(
        compose["services"], script_services, failures)
    stats["healthcheck_script_entries"] = len(script_services)
    if not script_services:
        fail(failures, "反空扫", "health-check.sh services 数组非空", "0", "scripts/health-check.sh")

    # --- 检查 6 ---
    stats["danger_unwarned"] = check_danger_warnings(issue_texts, failures)

    # --- 检查 7 ---
    # ISSUE/ 前缀锚点条件校验（ISSUE/50 §四门禁接线）：ISSUE/ 被 .gitignore（仅本机），
    # CI runner 上必然缺席 ⇒ 跳过锚点并提示；本机在场 ⇒ 照常校验存在性。
    # 真源只有一个：issue_dir_exists()（与检查 3 共用）。
    stats["trivyignore_anchors"] = check_trivyignore_anchors(
        read_lines(".trivyignore.yaml"), failures, issue_dir_exists())

    # CI 形态跳过留痕：检查 3 每文件至多 1 条、检查 7 每锚点 1 条，全量汇入 stats。
    # self-check 样本也走同一路径，跑完自检由用例自行断言并清空。
    stats["issue_anchor_skips"] = len(ISSUE_ANCHOR_SKIPS)

    return stats


def print_report(stats: dict, failures: list[dict]) -> None:
    print("=" * 78)
    print("文档一致性门禁（check-docs-consistency）")
    print("-" * 78)
    print("反空扫自证（以下计数必须全部 > 0，否则门禁本身失效）：")
    for key in ("backend_test_files", "backend_test_classes", "frontend_spec_files",
                "compose_services", "compose_redis_db_keys", "docs_md_files",
                "issue_md_files", "backend_declared_spots", "frontend_declared_spots",
                "layout_declared_spots", "healthcheck_script_entries"):
        if key in stats:
            print(f"  {key} = {stats[key]}")
    print("-" * 78)
    ok = [
        f"✓ 1.后端测试规模（实测 {stats.get('backend_test_files')} 文件 / {stats.get('backend_test_classes')} 类，声明 {stats.get('backend_declared_spots')} 处）",
        f"✓ 2.前端测试文件数（实测 {stats.get('frontend_spec_files')}，声明 {stats.get('frontend_declared_spots')} 处）",
        f"✓ 3.路径引用有效性（悬空 0）",
        f"✓ 4.Redis 库号兜底处数（compose 实测 {stats.get('compose_redis_db_keys')}，声明 {stats.get('layout_declared_spots')} 处）",
        f"✓ 5.健康巡检覆盖完整性（healthcheck 服务 {stats.get('healthcheck_services_required')}，巡检数组 {stats.get('healthcheck_script_entries')}）",
        f"✓ 6.危险指令警示（无警示 0）",
        f"✓ 7.CVE 豁免证据锚点（锚点 {stats.get('trivyignore_anchors')}，悬空 0）",
    ]
    if not failures:
        for line in ok:
            print(line)
        if stats.get("issue_anchor_skips"):
            # CI 形态留痕：跳过数 > 0 时逐条输出（本机 ISSUE/ 在场时恒为 0，不打印）。
            print(f"ℹ ISSUE/ 锚点跳过 {stats['issue_anchor_skips']} 处（ISSUE/ 目录不存在，"
                  "本地台账不入库、CI runner 上属预期 —— 仓库内路径引用仍严格校验）：")
            for s in ISSUE_ANCHOR_SKIPS:
                print(f"  ℹ {s}")
        print("=" * 78)
        print("PASS: 文档与实况一致。")
        return
    print(f"✗ 失败 {len(failures)} 项（一次列全，便于批量修）：")
    for f in failures:
        print(f"  ✗ {f['check']}：期望 {f['expected']}，实测 {f['actual']}（{f['where']}）")
    print("=" * 78)
    print(f"FAIL: {len(failures)} 项不一致 —— 逐项修复后重跑本脚本。")


# ---------------------------------------------------------------------------
# 检查 8：门禁自检（违规样本必须红并点名；合规样本必须不误报）
# ---------------------------------------------------------------------------

class SelfCheckError(Exception):
    pass


def expect_fail(name: str, fn, *fragments: str) -> None:
    try:
        fn()
    except AssertionError as exc:
        message = str(exc)
        missing = [frag for frag in fragments if frag not in message]
        if missing:
            raise SelfCheckError(
                f"自检失败：{name} —— 门禁红是红了，但报错没点名 {missing}。消息：\n{message}")
        return
    raise SelfCheckError(f"自检失败：{name} —— 违规样本没有让门禁红（恒绿 = 门禁不存在）")


def expect_pass(name: str, fn) -> None:
    try:
        fn()
    except AssertionError as exc:
        raise SelfCheckError(f"自检失败：{name} —— 合规样本被误报。消息：\n{exc}")


def run_self_check() -> int:
    """第 8 项：喂违规样本 → 红并点名；合规样本 → 不误报。"""
    collected: list[dict] = []
    A = "预期失败点"
    print("=" * 78)
    print("门禁自检（--self-check）：违规样本必须红并点名；合规样本必须不误报")
    print("-" * 78)

    # --- 1 后端规模：声明 999 vs 实测 3 ---
    def backend_violation():
        f: list[dict] = []
        check_backend_counts(
            [{"file": "CLAUDE.md", "line": 1, "kind": "classes+files", "files": 999, "classes": 998, "text": ""}],
            None, 3, 2, f)
        if f:
            raise AssertionError(f"{f[0]['check']}：期望 {f[0]['expected']}，实测 {f[0]['actual']}（{f[0]['where']}）")
    expect_fail("1.后端规模违规", backend_violation, "999", "3")
    print("  ✓ 违规样本 1：后端规模声明 999/998 vs 实测 3/2 → 红并点名数字")

    # --- 1 分项加总：总和 10 vs 分项和 6 ---
    def breakdown_violation():
        f: list[dict] = []
        check_backend_counts([], {"file": "docs/testing.md", "rows": {"zxyz-a": (4, 4), "zxyz-b": (2, 2)},
                                  "total_files": (10, 1333), "total_classes": (10, 1334)}, 6, 6, f)
        if f:
            raise AssertionError(f"{f[0]['check']}：期望 {f[0]['expected']}，实测 {f[0]['actual']}（{f[0]['where']}）")
    expect_fail("1b.分项加总违规", breakdown_violation, "1333")
    print("  ✓ 违规样本 1b：分项和 6 ≠ 总和 10 → 红并点名 docs/testing.md:1333")

    # --- 2 前端数量 ---
    def frontend_violation():
        f: list[dict] = []
        check_frontend_count([{"file": "CLAUDE.md", "line": 2, "files": 26}], 68, f)
        if f:
            raise AssertionError(f"{f[0]['check']}：期望 {f[0]['expected']}，实测 {f[0]['actual']}（{f[0]['where']}）")
    expect_fail("2.前端数量违规", frontend_violation, "26", "68")
    print("  ✓ 违规样本 2：前端声明 26 vs 实测 68 → 红并点名（历史真实事故形态）")

    # --- 3 悬空引用（无归档语境必须红；有归档语境必须放行） ---
    lines_bad = ["常规行", "参见 `ISSUE/38-NOPE-REVIEW.md` 的结论", "尾行"]
    dangling = collect_dangling_refs("FAKE.md", lines_bad)
    if not dangling:
        raise SelfCheckError("自检失败：3.悬空引用样本没有被抓到")
    if "ISSUE/38-NOPE-REVIEW.md" not in str(dangling):
        raise SelfCheckError(f"自检失败：3.悬空引用红了但没点名：{dangling}")
    print("  ✓ 违规样本 3：悬空引用 ISSUE/38-NOPE-REVIEW.md → 抓到并点名")
    lines_ok = ["已于 2026-10 归档移出，等价原件在 oldmd/：", "参见 `ISSUE/38-NOPE-REVIEW.md`"]
    def archived_ok():
        if collect_dangling_refs("FAKE.md", lines_ok):
            raise AssertionError("归档语境仍被误报")
    expect_pass("3b.归档指针放行", archived_ok)
    print("  ✓ 合规样本 3b：已归档 + oldmd 语境 → 不误报")

    # --- 3c CI 形态（ISSUE/ 目录不存在）→ 检查 3 不误报 ---
    # ISSUE/ 被 .gitignore（仅本机保留），CI runner 上文档里的 ISSUE/ 引用必然悬空：
    # 这是预期环境形态而非文档缺陷 ⇒ issue_present=False 时必须跳过 ISSUE/ 引用并留痕；
    # 同批样本里的仓库内悬空引用（docs/…）不受豁免，仍必须红（防「CI 豁免」被放大成盲区）。
    docs_ci = {"FAKE.md": lines_bad, "REAL.md": ["参见 `docs/no-such-file.md` 的结论"]}
    ci_failures: list[dict] = []
    check_path_refs(docs_ci, ci_failures, issue_present=False)
    # failure dict 结构 = {check, expected, actual, where}；悬空引用名在 actual 的
    # 「悬空引用 <ref>」里（检查 3 的 fail() 语义），断言按 actual 匹配。
    if any("悬空引用 ISSUE/" in d["actual"] for d in ci_failures):
        raise SelfCheckError(f"自检失败：3c.ISSUE/ 缺席时检查 3 误报了 ISSUE 引用：{ci_failures}")
    if not any("悬空引用 docs/no-such-file.md" in d["actual"] for d in ci_failures):
        raise SelfCheckError(f"自检失败：3c.CI 形态下仓库内悬空引用（docs/…）未被点名：{ci_failures}")
    if not any("检查 3" in s for s in ISSUE_ANCHOR_SKIPS):
        raise SelfCheckError(f"自检失败：3c.ISSUE/ 缺席时检查 3 应留下跳过痕迹：{ISSUE_ANCHOR_SKIPS}")
    ISSUE_ANCHOR_SKIPS.clear()
    # 对照：ISSUE/ 在场（本机形态）时同一悬空样本必须红 —— 跳过逻辑没有吞掉真违规。
    local_failures: list[dict] = []
    check_path_refs(docs_ci, local_failures, issue_present=True)
    if not any("悬空引用 ISSUE/38-NOPE-REVIEW.md" in d["actual"] for d in local_failures):
        raise SelfCheckError(f"自检失败：3c.本机形态（ISSUE/ 在场）悬空 ISSUE 引用未被点名：{local_failures}")
    if not any("悬空引用 docs/no-such-file.md" in d["actual"] for d in local_failures):
        raise SelfCheckError(f"自检失败：3c.本机形态悬空 docs 引用未被点名：{local_failures}")
    print("  ✓ 合规样本 3c：ISSUE/ 目录缺席（CI 形态）→ 检查 3 不误报 ISSUE/ 引用且留跳过痕迹；"
          "同批 docs/ 悬空仍红；本机形态对照 → ISSUE/ 悬空仍红")

    # --- 4 Redis 处数 ---
    def redis_violation():
        f: list[dict] = []
        check_redis_db_count([{"line": 11, "count": 10, "text": ""}], 9, f)
        if f:
            raise AssertionError(f"{f[0]['check']}：期望 {f[0]['expected']}，实测 {f[0]['actual']}（{f[0]['where']}）")
    expect_fail("4.Redis 处数违规", redis_violation, "10", "9")
    print("  ✓ 违规样本 4：声明 10 处 vs compose 9 处 → 红并点名")

    # --- 5 巡检覆盖 ---
    def coverage_violation():
        f: list[dict] = []
        services = {"admin-service": {"container_name": "zxyz-admin-service", "has_healthcheck": True},
                    "mysql": {"container_name": "zxyz-mysql", "has_healthcheck": True}}
        check_healthcheck_coverage(services, ["zxyz-mysql"], f)
        if f:
            raise AssertionError(f"{f[0]['check']}：期望 {f[0]['expected']}，实测 {f[0]['actual']}（{f[0]['where']}）")
    expect_fail("5.巡检覆盖违规", coverage_violation, "zxyz-admin-service")
    print("  ✓ 违规样本 5：healthcheck 有 admin 而巡检数组缺 → 红并点名 zxyz-admin-service")

    # --- 6 危险指令（警示窗口：上 12 行 / 下 3 行；只对真命令形态要求） ---
    # 无警示的 fenced 命令 → 红
    unwarned = collect_unwarned_danger(
        "FAKE.md", ["```powershell", "git push origin dev", "```"] + ["普通行"] * 5)
    if not unwarned:
        raise SelfCheckError("自检失败：6.fenced 内无警示命令没有被抓到")
    # 行首命令形态（无 fence）→ 同样红
    unwarned2 = collect_unwarned_danger("FAKE.md", ["git add -A && git commit -m x"] + ["普通行"] * 5)
    if not unwarned2:
        raise SelfCheckError("自检失败：6.行首无警示命令没有被抓到")
    # 警示在上方 2 行（fence 外）→ 放行
    warned_up2 = ["⛔ 需授权，会部署到生产", "```powershell", "git push origin dev", "```"]
    if collect_unwarned_danger("FAKE.md", warned_up2):
        raise SelfCheckError("自检失败：6.警示在上方 2 行却被误报")
    # 警示在上方 11 行 → 仍放行（窗口内）；13 行 → 红（窗口外）
    warned_up11 = ["⛔ 需授权"] + ["普通行"] * 10 + ["```powershell", "git push origin dev", "```"]
    if collect_unwarned_danger("FAKE.md", warned_up11):
        raise SelfCheckError("自检失败：6.警示在上方 11 行（窗口内）却被误报")
    warned_up13 = ["⛔ 需授权"] + ["普通行"] * 12 + ["```powershell", "git push origin dev", "```"]
    if not collect_unwarned_danger("FAKE.md", warned_up13):
        raise SelfCheckError("自检失败：6.警示在上方 13 行（窗口外）应仍然红")
    # 散文性提及（blockquote 里的风险描述）→ 不是命令，放行
    prose = ["> 另外 `git add -A` 会**暂存工作区全部未提交改动**——提交前先核对。"]
    if collect_unwarned_danger("FAKE.md", prose):
        raise SelfCheckError("自检失败：6.散文性风险描述被误报为需警示的命令")
    # 表格里的提及 → 放行
    table = ["| rollback 说明 | 需手工 `git checkout <sha> -- file` |"]
    if collect_unwarned_danger("FAKE.md", table):
        raise SelfCheckError("自检失败：6.表格内提及被误报")
    print("  ✓ 违规样本 6：fenced/行首命令无警示 → 红；警示上 2/上 11 行 → 放行、上 13 行 → 红（窗口边界正确）；散文/表格提及 → 放行")

    # --- 7 trivyignore 锚点 ---
    def anchor_violation():
        f: list[dict] = []
        check_trivyignore_anchors(
            ["statement: |", "  证据见 ISSUE/99-DOES-NOT-EXIST.md"], f)
        if f:
            raise AssertionError(f"{f[0]['check']}：期望 {f[0]['expected']}，实测 {f[0]['actual']}（{f[0]['where']}）")
    expect_fail("7.豁免锚点悬空", anchor_violation, "ISSUE/99-DOES-NOT-EXIST.md")
    def anchor_ok():
        f: list[dict] = []
        check_trivyignore_anchors(
            ["  已归档：", "  证据见 ISSUE/99-DOES-NOT-EXIST.md（oldmd/99-DOES-NOT-EXIST.md）"], f)
        if f:
            raise AssertionError(f"归档语境仍被误报：{f}")
    expect_pass("7b.归档锚点放行", anchor_ok)
    print("  ✓ 违规样本 7：trivyignore 悬空 ISSUE 锚点 → 红并点名；归档 + oldmd → 放行")

    # --- 7c CI 形态（ISSUE/ 目录不存在）→ 检查 7 不误报（本任务新增用例） ---
    # 与 3c 同一设计、同一真源（issue_dir_exists()）：trivyignore 的 ISSUE/ 前缀锚点在
    # CI runner 上必然悬空 —— issue_present=False 时跳过并留痕，oldmd 語境不受影响；
    # 对照 issue_present=True（本机形态）时同一锚点必须红 —— 跳过逻辑没吞掉真违规。
    anchors_ci = ["  证据见 ISSUE/47-CVE-SSE-HEADER-BYPASS-2026-10-09.md（本机台账）"]
    def anchor_ci_ok():
        f: list[dict] = []
        check_trivyignore_anchors(anchors_ci, f, issue_present=False)
        if f:
            raise AssertionError(f"ISSUE/ 缺席时检查 7 误报：{f}")
    expect_pass("7c.CI 形态锚点不误报", anchor_ci_ok)
    if not any("ISSUE/47-CVE-SSE-HEADER-BYPASS-2026-10-09.md" in s for s in ISSUE_ANCHOR_SKIPS):
        raise SelfCheckError(f"自检失败：7c.跳过未留痕（应含锚点名）：{ISSUE_ANCHOR_SKIPS}")
    ISSUE_ANCHOR_SKIPS.clear()
    # 对照（7c-2）：本机形态（issue_present=True）时悬空锚点必须仍红 —— 跳过逻辑没吞掉真违规。
    # 刻意用确定不存在的锚点名（47 号在本机真实存在，用它对照会假绿）。
    anchors_local = ["  证据见 ISSUE/99-DOES-NOT-EXIST.md（本机台账）"]
    def anchor_ci_present_still_red():
        f: list[dict] = []
        check_trivyignore_anchors(anchors_local, f, issue_present=True)
        if f:
            raise AssertionError(f"本机形态悬空锚点应红却被放行：{f}")
    expect_fail("7c-2.本机形态锚点仍红", anchor_ci_present_still_red,
                "ISSUE/99-DOES-NOT-EXIST.md")
    ISSUE_ANCHOR_SKIPS.clear()
    print("  ✓ 合规样本 7c：ISSUE/ 目录缺席（CI 形态）→ 检查 7 不误报且留痕；"
          "本机形态对照 → 悬空锚点仍红（7c-2）")

    # --- compose 重复键 ---
    try:
        scan_compose("services:\n  mysql:\n    healthcheck: {}\n  mysql:\n    healthcheck: {}\n")
        raise SelfCheckError("自检失败：compose 重复服务键没有被拒绝")
    except ComposeScanError as exc:
        if "重复" not in str(exc):
            raise SelfCheckError(f"自检失败：compose 重复键报错没说『重复』：{exc}")
    try:
        scan_compose("services:\n  mysql:\n    healthcheck: {}\nvolumes:\n  data:\nvolumes:\n  other:\n")
        raise SelfCheckError("自检失败：compose 顶层重复键没有被拒绝")
    except ComposeScanError:
        pass
    ok_compose = scan_compose("services:\n  mysql:\n    healthcheck: {}\n    container_name: zxyz-mysql\n")
    if ok_compose["services"].get("mysql", {}).get("has_healthcheck") is not True:
        raise SelfCheckError("自检失败：合规 compose 样本被误报（healthcheck 未识别）")
    print("  ✓ 违规样本 8：compose 服务/顶层重复键 → 拒绝；合规样本 → 正常解析")

    print("=" * 78)
    print("SELF-CHECK PASS: 全部门禁「真的会红」且「不误报」。")
    return 0


def main() -> int:
    # GBK 控制台防崩（ISSUE/50 §3.2）：失败路径 print 含 ✗（U+2717），在 GBK 代码页
    # 控制台会抛 UnicodeEncodeError ——「PASS 时正常、FAIL 时崩溃」= 门禁在最需要响的
    # 时候哑火。hasattr 保护非文本流场景（如被重定向/包装的 IO）；errors="replace"
    # 保证任何字符都可打印，绝不因输出编码二次崩溃。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description="文档一致性门禁")
    parser.add_argument("--json", action="store_true", help="机器可读输出")
    parser.add_argument("--self-check", action="store_true", help="只跑第 8 项门禁自检")
    args = parser.parse_args()

    if args.self_check:
        code = run_self_check()
        return code

    failures: list[dict] = []
    try:
        stats = run_gate(failures)
    except FileNotFoundError as exc:
        print(f"环境错误：{exc}（是否在仓库根目录运行？）")
        return 2

    if args.json:
        print(json.dumps({"stats": stats, "failures": failures,
                          "pass": not failures}, ensure_ascii=False, indent=2))
    else:
        print_report(stats, failures)
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
