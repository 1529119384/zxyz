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

    python3 scripts/check-env-example-values.py [.env.example]

退出码：0 = 通过；1 = 发现疑似真实凭证/非法取值。
CI 的 lint-config 作业直接调用本脚本（同一份逻辑，本地可复跑，不会出现两套口径漂移）。
"""

from __future__ import annotations

import re
import sys

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
        print("FAIL: .env.example 出现疑似真实凭证/非法取值。")
        print("  该文件在 .gitleaks.toml 里是**整文件豁免** ⇒ 本断言是它唯一的补偿门禁。")
        for lineno, line, why in bad:
            print(f"  line {lineno}: {why}")
            print(f"    {line[:120]}")
        print("  修法：真实凭证**只允许**存在于服务器 .env；模板里一律写 CHANGE_ME_* 占位符。")
        return 1

    print("PASS: .env.example 取值形态全部合法（占位符/布尔/数字/URL/引用/公开标识）")
    return 0


def main() -> int:
    # Windows 控制台默认 GBK 会让「⇒ / ✓」等字符抛 UnicodeEncodeError；
    # CI 是 UTF-8 不受影响，但本地复跑不该因此炸出无关 traceback。
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):  # pragma: no cover
        pass

    path = sys.argv[1] if len(sys.argv) > 1 else ".env.example"
    try:
        return check(path)
    except FileNotFoundError:
        print(f"FAIL: 找不到 {path}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
