#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`.env.example` 取值形态断言 —— gitleaks 整文件豁免的补偿门禁（审计 I-19）。

## 为什么需要它

`.gitleaks.toml` 的 `[[allowlists]]` 用 `paths = ['''(^|/)\\.env\\.example$''']`
把 `.env.example` **整文件**豁免成了永久扫描盲区 —— 真有人往模板里写入真实密钥，
gitleaks 不会拦（豁免粒度原则见该文件头 11-24 行的血泪教训：豁免太粗把真命中一起掩蔽了）。
该文件头部自己写着「请定期复核」，但**复核没有触发器** ⇒ 等于没有。

## 取舍：为什么不直接取消豁免

取消豁免会让模板里**本就必须存在**的取值（`CHANGE_ME_*` 占位符、纯 URL、端口…）
变成误报，逼人再往 `.gitleaks.toml` 加一条豁免 —— 盲区不减反增。
所以本脚本的做法是把「该文件里只允许出现哪几类取值」变成**硬断言**：
写进去就会红，盲区不再依赖人的记忆力。

## 判据（三条，任一命中即 FAIL）

**判据 1 · 取值形态白名单**（整值匹配，任一命中即可）

| 形态 | 例 |
|---|---|
| 空值 | `ADMIN_INIT_USERNAME=` |
| 占位符 | `CHANGE_ME_MYSQL_PASSWORD` |
| 布尔 | `true` / `false` |
| 数字 / 端口 | `6379` / `43200` |
| 纯 URL | `https://oss-cn-shenzhen.aliyuncs.com` |
| `${...}` 引用 | `${DATA_DIR:-./data}` |
| 公开标识 | `cn-shenzhen` / `your-bucket-name` / `./data` / `serverIdentity` |
| 通配 | `*` |

**判据 2 · 高熵单串**：长度 ≥ 24 的单一 token，且同时含大写、小写、数字
（真实 AK/SK、随机口令的典型形态）。此判据只看**取值形状**，不看键名 ——
所以「把密钥写在看起来无害的键上」也会被拦下。

**判据 3 · 敏感键名**：键名以 `PASSWORD`/`PASSWD`/`SECRET`/`TOKEN`/`APIKEY`/
`ACCESS_KEY_ID`/`ACCESS_KEY_SECRET` 结尾时，取值只允许是「空 / `CHANGE_ME_` 开头 /
`${...}` 引用」。

> 判据 3 **刻意不覆盖** `*_USERNAME`、`*_TIMEOUT`、`*_IDENTITY_KEY`：
> 前者是文档写明的固定专用账户名（`zxyz_project` 等），后两者是超时数字与请求头**名**，
> 都不是机密。把它们判红会制造假阳性，反而逼人加豁免。

## 用法

    python3 scripts/check-env-example-values.py [path ...]

**自动发现（CI 形态，不传参时启用）**：不带参数时，脚本自动遍历仓库内**全部**名为
`.env.example` 的文件（`git ls-files` 优先，异常时回退 `pathlib.rglob`，排除
`node_modules/`/`.git/`/`target/`/`dist/`）逐个校验 —— 任何一个 `.env.example` 都逃不出本门禁。

> 为什么必须自动发现（58 号台账 §二.5，46/49/51 三轮点名的盲区）：
> `.gitleaks.toml` 的整文件豁免正则是 `(^|/)\\.env\\.example$` —— 它**同时**豁免
> 根目录 `.env.example` **和** `ZXYZdatabaseFront/.env.example`（以及未来任何目录下的
> 同名模板）。而 CI 此前只对根目录那一个传参校验 ⇒ 前端子仓的模板是**豁免了却没人看**
> 的永久盲区，将来再出现第 3 个 `.env.example` 也一样会漏。按文件名自动发现 +
> 逐文件校验，才能让门禁覆盖面与 gitleaks 豁免面**严格对齐**，且新增模板零维护。

也兼容显式传参（逐个文件校验，任一失败即 exit 1）——便于本地只复查某个文件。

退出码：0 = 通过；1 = 发现疑似真实凭证/非法取值（或文件缺失）。
CI 的 lint-config 作业直接调用本脚本（同一份逻辑，本地可复跑，不会出现两套口径漂移）。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

# --- 判据 1：允许的取值形态（整值匹配） ---
ALLOWED = [
    r"^$",                                                  # 空值
    r"^CHANGE_ME_[A-Z0-9_]*$",                              # 占位符
    r"^(true|false)$",                                      # 布尔
    r"^[0-9]+$",                                            # 数字 / 端口
    r"^https?://[A-Za-z0-9._~:/?#@!$&()*+,;=%-]*$",         # 纯 URL
    r"^\$\{[^}]*\}$",                                       # ${VAR} / ${VAR:-default}
    r"^[A-Za-z0-9._/-]+$",                                  # 标识类：桶名/区域/路径/服务地址
    r"^\*$",
]
RX = [re.compile(p) for p in ALLOWED]

# --- 判据 2：高熵单串 ---
ENTROPY_MIN_LEN = 24
HAS_UPPER = re.compile(r"[A-Z]")
HAS_LOWER = re.compile(r"[a-z]")
HAS_DIGIT = re.compile(r"[0-9]")

# --- 判据 3：敏感的**取值型**键名后缀 ---
SECRET_KEY_RX = re.compile(
    r"(?:^|_)(?:PASSWORD|PASSWD|SECRET|TOKEN|APIKEY|API_KEY|ACCESS_KEY_ID|ACCESS_KEY_SECRET)$"
)
PLACEHOLDER_OK = re.compile(r"^$|^CHANGE_ME_|^\$\{")


def _looks_high_entropy(value: str) -> bool:
    """单一 token、够长、三类字符齐全 —— 真实凭证的典型形态。"""
    if len(value) < ENTROPY_MIN_LEN:
        return False
    # 带分隔符/空格的（URL、路径、句子）不算「单串凭证」
    if any(ch in value for ch in " \t:/?#@"):
        return False
    return bool(
        HAS_UPPER.search(value) and HAS_LOWER.search(value) and HAS_DIGIT.search(value)
    )


def check(path: str) -> int:
    bad: list[tuple[int, str, str]] = []
    with open(path, encoding="utf-8") as handle:
        for lineno, raw in enumerate(handle, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                bad.append((lineno, line, "不是 KEY=VALUE 形式"))
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip()

            # 判据 1
            if not any(rx.match(value) for rx in RX):
                bad.append((lineno, line, "取值形态不在白名单内"))
                continue
            # 判据 2
            if _looks_high_entropy(value):
                bad.append(
                    (lineno, line, f"取值形似高熵凭证（长度 {len(value)}，大小写+数字齐全）")
                )
                continue
            # 判据 3
            if SECRET_KEY_RX.search(key) and not PLACEHOLDER_OK.match(value):
                bad.append((lineno, line, f"敏感键 {key} 的取值非 空/CHANGE_ME_/${{}} 引用"))
                continue

    if bad:
        print(f"FAIL: {path} 出现疑似真实凭证/非法取值。")
        print("  该文件命中 .gitleaks.toml 的整文件豁免 ⇒ 本断言是它唯一的补偿门禁。")
        for lineno, line, why in bad:
            print(f"  line {lineno}: {why}")
            print(f"    {line[:120]}")
        print("  修法：真实凭证**只允许**存在于服务器 .env；模板里一律写 CHANGE_ME_* 占位符。")
        return 1

    print(f"PASS: {path} 取值形态全部合法（占位符/布尔/数字/URL/引用/公开标识）")
    return 0


def discover_env_examples() -> list[str]:
    """自动发现仓库内全部 `.env.example`（58 号 §二.5：覆盖面须与 gitleaks 豁免正则对齐）。

    优先用 `git ls-files`（与 CI 检出的文件集一致，天然尊重 .gitignore）；
    git 不可用/非仓库目录时回退 `pathlib.rglob`，并排除依赖/构建目录与版本库目录。
    """
    try:
        out = subprocess.run(
            ["git", "ls-files", "--", "**/.env.example", ".env.example"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=True,
        ).stdout
        found = [line.strip() for line in out.splitlines() if line.strip()]
        if found:
            return found
    except (OSError, subprocess.SubprocessError):
        pass  # 无 git / 不在仓库内：走 rglob 回退

    skip_parts = {"node_modules", ".git", "target", "dist", "build", ".venv", "venv"}
    found = []
    for path in Path(".").rglob(".env.example"):
        if any(part in skip_parts for part in path.parts):
            continue
        found.append(path.as_posix())
    return sorted(found)


def main() -> int:
    # Windows 控制台默认 GBK 会让「⇒ / ✓」等字符抛 UnicodeEncodeError；
    # CI 是 UTF-8 不受影响，但本地复跑不该因此炸出无关 traceback。
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):  # pragma: no cover
        pass

    paths = [arg for arg in sys.argv[1:] if not arg.startswith("-")]
    if not paths:
        # 自动发现形态（CI 默认）：与 gitleaks 豁免正则 `(^|/)\.env\.example$` 的覆盖面对齐
        paths = discover_env_examples()

    if not paths:
        print("FAIL: 自动发现 0 个 .env.example —— 门禁扫描失效，宁可响亮失败也不静默通过")
        return 1

    exit_code = 0
    for path in paths:
        try:
            if check(path) != 0:
                exit_code = 1  # 逐文件校验，任一失败仍继续跑完其余文件（报告完整）
        except FileNotFoundError:
            print(f"FAIL: 找不到 {path}")
            exit_code = 1
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
