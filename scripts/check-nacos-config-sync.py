#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""nacos-config/ 与后端代码默认值的「等价性」门禁（审计 D2）。

## 为什么需要它

每个服务的 `application.yml` 都有：

    spring.config.import:
      - classpath:application-common.yml
      - nacos:zxyz-static.yml?group=ZXYZ&refreshEnabled=false
      - nacos:zxyz-dynamic.yml?group=ZXYZ&refreshEnabled=true
      - nacos:zxyz-<svc>.yml?group=ZXYZ&refreshEnabled=false

而 **Nacos 配置的优先级高于 jar 内 yml**。所以 `nacos-config/*.yml` 一旦被导入，
任何「与运行期实际取值不同」的键都会**立刻改变行为**（`zxyz-dynamic.yml` 带
`refreshEnabled=true`，改完即时生效，不需要重启）。

⇒ 本仓契约：**导入 `nacos-config/` 必须是行为等价的动作**。
   本脚本是这条契约的机器检查，也是「改 nacos-config 必须 import」那条人工流程的前置守卫。

## 比较基线：只统计「真正 import 了该 dataId 的服务」

这一点必须精确，否则会造出**假阳性**。例如 `zxyz-gateway` 的 `spring.config.import`
里**没有** `zxyz-static.yml`（它只引 `application-common.yml` + `zxyz-dynamic.yml`），
所以 gateway 在 `spring.data.redis.timeout` / `sa-token.is-log` 上与其它服务的差异
**与 `zxyz-static.yml` 无关**，不该被判为冲突。

每个服务的「运行期取值」= `application-common.yml` → `application.yml` →
非 dev 的 profile 文件（如 `application-prod.yml`，后者覆盖前者）。
`application-dev.yml` / `application-test.yml` **刻意排除**：dev 档的宽松默认
（验证码回显、localhost 数据源/CORS）是本仓 sanctioned 的做法，它们与 Nacos 的差异
以 `[DEV-DRIFT]` 警告形式列出，不算失败。

## 判据

| 类别 | 含义 | 是否阻断 |
|---|---|---|
| YAML 不合法 / 顶层重复 key | SnakeYAML 会静默丢弃前一个 | **阻断** |
| `[DIFF]` | nacos 的值 ≠ 某个消费方在**非 dev 档**的实际取值 | **阻断**（导入即改行为） |
| `[UNCONSUMED]` | 没有任何服务 import 这个 dataId | 警告（该文件当前是死配置） |
| `[EXTRA]` | nacos 有、消费方代码里没有 | 警告。多为 `@Value` 默认值的显式化 ⇒ 需人工确认与 Java 默认值相等 |
| `[MISS]` | 代码有、nacos 没有 | 提示。导入不会删除代码侧的键 |
| `[DEV-DRIFT]` | 仅 dev 档取值不同 | 警告。只在「把配置导入到本地 Nacos」时才需留意 |

## 用法

    python3 scripts/check-nacos-config-sync.py        # 阻断项 + 汇总
    python3 scripts/check-nacos-config-sync.py -v     # 连带打印 EXTRA / MISS / DEV-DRIFT 明细

退出码：0 = 可安全导入；1 = 存在阻断项。
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import sys

try:
    import yaml
    from yaml.constructor import SafeConstructor
except ImportError:  # pragma: no cover
    sys.stderr.write("缺少 PyYAML。安装：pip install pyyaml（CI 由 workflow 步骤自动安装）\n")
    sys.exit(2)

DEV_PROFILE_MARKERS = ("-dev", "-test", "-local")
NACOS_REF = re.compile(r"nacos:([^?]+)")


class DuplicateKeyError(Exception):
    pass


def _no_dup_mapping(loader, node, deep=False):
    """拒绝重复 key 的 mapping 构造器（PyYAML 默认只保留后一个，与 SnakeYAML 同样危险）。"""
    keys = []
    for key_node, _ in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in keys:
            raise DuplicateKeyError(f"重复 key: {key!r}（near line {key_node.start_mark.line + 1}）")
        keys.append(key)
    return SafeConstructor.construct_mapping(loader, node, deep=deep)


class StrictLoader(yaml.SafeLoader):
    """拒绝重复 key 的 SafeLoader。"""


StrictLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _no_dup_mapping)


def load_yaml(path: str) -> dict:
    with open(path, encoding="utf-8") as handle:
        raw = handle.read()
    try:
        return yaml.load(raw, Loader=StrictLoader) or {}
    except DuplicateKeyError:
        raise
    except yaml.YAMLError as exc:
        raise DuplicateKeyError(f"YAML 解析失败: {exc}") from exc


def flatten(node, prefix: str = "", out: dict | None = None) -> dict:
    if out is None:
        out = {}
    if isinstance(node, dict):
        for key, value in node.items():
            flatten(value, f"{prefix}.{key}" if prefix else str(key), out)
    elif isinstance(node, list):
        out[prefix] = repr(node)
    else:
        out[prefix] = "" if node is None else str(node)
    return out


class Repo:
    """读「代码侧配置」与「nacos 侧配置」的小工具。"""

    def __init__(self, nacos_dir: str, backend_dir: str) -> None:
        self.nacos_dir = nacos_dir
        self.backend_dir = backend_dir
        self.common = os.path.join(
            backend_dir, "zxyz-common", "src", "main", "resources", "application-common.yml"
        )
        if not os.path.isfile(self.common):
            raise SystemExit(f"找不到 {self.common}（--backend-dir 指错了？）")
        self._runtime_cache: dict[str, dict[str, str]] = {}

    # --- 模块发现 ---
    def modules(self) -> list[str]:
        """真实服务模块 = 有 src/main/resources/application.yml 的模块。

        不能用「有 src/main/resources/ 目录」当判据：`xzyz-common`（只有
        application-common.yml）与 `xzyz-starter`（只有 META-INF）也会命中。
        """
        found = [
            os.path.basename(path)
            for path in glob.glob(os.path.join(self.backend_dir, "zxyz-*"))
            if os.path.isfile(
                os.path.join(path, "src", "main", "resources", "application.yml")
            )
        ]
        return sorted(found)

    def module_for(self, data_id: str) -> str | None:
        """zxyz-foo-service.yml -> zxyz-foo-service；zxyz-gateway.yml -> xzyz-gateway。"""
        stem = data_id[:-4]  # 去掉 .yml
        for candidate in (stem, f"{stem}-service"):
            if os.path.isfile(
                os.path.join(
                    self.backend_dir, candidate, "src", "main", "resources", "application.yml"
                )
            ):
                return candidate
        return None

    def _resources(self, module: str) -> str:
        return os.path.join(self.backend_dir, module, "src", "main", "resources")

    def nacos_imports(self, module: str) -> list[str]:
        """该模块 spring.config.import 里引用的所有 nacos dataId（按出现顺序）。"""
        raw = load_yaml(os.path.join(self._resources(module), "application.yml"))
        node = raw
        for part in ("spring", "config", "import"):
            node = node.get(part) if isinstance(node, dict) else None
            if node is None:
                return []
        if isinstance(node, str):
            node = [node]
        if not isinstance(node, list):
            return []
        found = []
        for item in node:
            if isinstance(item, str):
                match = NACOS_REF.search(item)
                if match:
                    found.append(match.group(1).strip())
        return found

    # --- 运行期取值 ---
    def _ordered_sources(self, module: str, dev: bool) -> list[str]:
        """返回参与比较的配置文件（application.yml 在前，其它非 dev profile 在后）。"""
        base = self._resources(module)
        primary = os.path.join(base, "application.yml")
        others = []
        for path in sorted(glob.glob(os.path.join(base, "application*.yml"))):
            name = os.path.basename(path)
            if name in ("application.yml", "application-common.yml"):
                continue
            if any(marker in name for marker in DEV_PROFILE_MARKERS) == dev:
                others.append(path)
        return [primary] + others

    def runtime_values(self, module: str, dev: bool = False) -> dict[str, str]:
        """该模块指定档位的**实际取值**（后者覆盖前者，即 profile 优先）。"""
        cache_key = f"{module}|{dev}"
        if cache_key in self._runtime_cache:
            return self._runtime_cache[cache_key]
        merged: dict[str, str] = {}
        sources = [self.common] + self._ordered_sources(module, dev)
        for path in sources:
            if os.path.isfile(path):
                merged.update(flatten(load_yaml(path)))
        self._runtime_cache[cache_key] = merged
        return merged

    def consumers(self) -> dict[str, list[str]]:
        mapping: dict[str, list[str]] = {}
        for module in self.modules():
            for data_id in self.nacos_imports(module):
                mapping.setdefault(data_id, []).append(module)
        return mapping


def compare(
    label: str,
    data_id: str,
    nacos_cfg: dict,
    consumers: list[str],
    repo: Repo,
    verbose: bool,
) -> tuple[int, int]:
    """返回 (阻断项数, DEV-DRIFT 数)。"""
    runtime = {module: repo.runtime_values(module) for module in consumers}
    dev_runtime = {module: repo.runtime_values(module, dev=True) for module in consumers}

    blockers: list[str] = []
    extra: list[str] = []
    for key, value in nacos_cfg.items():
        owners = {m: runtime[m][key] for m in consumers if key in runtime[m]}
        if not owners:
            extra.append(key)
            continue
        for module, actual in owners.items():
            if value != actual:
                blockers.append(
                    f"  [DIFF] {key}\n"
                    f"        nacos      : {value[:130]}\n"
                    f"        {module} 实际: {actual[:130]}"
                )

    union = {}
    for module in consumers:
        for key, value in runtime[module].items():
            union.setdefault(key, value)
    miss = sorted(k for k in union if k not in nacos_cfg)

    dev_drift = sorted(
        k
        for k, value in nacos_cfg.items()
        if any(k in dev_runtime[m] and dev_runtime[m][k] != value for m in consumers)
    )

    print("=" * 78)
    print(f"{label}\n  dataId={data_id}  消费方={consumers}")
    print(
        f"  nacos={len(nacos_cfg)} 键 | 阻断 DIFF={len(blockers)} | "
        f"提示 EXTRA={len(extra)} MISS={len(miss)} DEV-DRIFT={len(dev_drift)}"
    )
    for line in blockers:
        print(line)
    if verbose:
        for key in extra:
            print(f"  [EXTRA] {key} = {nacos_cfg[key][:90]}")
        for key in miss:
            print(f"  [MISS] {key} = {union[key][:90]}")
        for key in dev_drift:
            print(f"  [DEV-DRIFT] {key}（仅 dev 档取值不同）")

    return len(blockers), len(dev_drift)


def main() -> int:
    parser = argparse.ArgumentParser(description="nacos-config 与代码默认值等价性门禁")
    parser.add_argument("-v", "--verbose", action="store_true", help="打印 EXTRA / MISS / DEV-DRIFT 明细")
    parser.add_argument("--nacos-dir", default="nacos-config")
    parser.add_argument("--backend-dir", default="ZXYZdatabaseBack")
    args = parser.parse_args()

    repo = Repo(args.nacos_dir, args.backend_dir)
    if not os.path.isdir(args.nacos_dir):
        raise SystemExit(f"找不到目录 {args.nacos_dir}")

    nacos_files = sorted(glob.glob(os.path.join(args.nacos_dir, "*.yml")))
    if not nacos_files:
        raise SystemExit(f"{args.nacos_dir} 下没有 *.yml")

    print("规则 1：YAML 合法性 + 顶层重复 key ...")
    broken = 0
    for path in nacos_files:
        try:
            load_yaml(path)
        except DuplicateKeyError as exc:
            print(f"  ✗ {path}: {exc}")
            broken += 1
    if broken:
        print(f"\nFAIL: {broken} 个文件不合法，无法导入。")
        return 1
    print(f"  ✓ {len(nacos_files)} 个文件可解析、无重复 key\n")

    consumers = repo.consumers()
    total_block = 0
    total_dev_drift = 0
    unconsumed: list[str] = []

    for path in nacos_files:
        data_id = os.path.basename(path)
        owners = consumers.get(data_id, [])
        if not owners:
            unconsumed.append(data_id)
            print("=" * 78)
            print(f"{data_id}  ->  [UNCONSUMED] ⚠ 没有任何服务 import 这个 dataId，当前是死配置")
            print("        （若本意是让它生效，请在对应服务的 spring.config.import 里加上；")
            print("          若是冗余文件，建议删除，避免「以为它在生效」。）")
            continue
        blocked, drift = compare(
            f"{data_id}  ->  {len(owners)} 个消费方（非 dev 档实际取值）",
            data_id,
            flatten(load_yaml(path)),
            owners,
            repo,
            args.verbose,
        )
        total_block += blocked
        total_dev_drift += drift

    print("=" * 78)
    if unconsumed:
        print(f"提示：[UNCONSUMED] {len(unconsumed)} 个：{', '.join(unconsumed)}")
    if total_block:
        print(f"FAIL: {total_block} 个 [DIFF] —— 导入会改变某个消费方的实际取值，必须先修正。")
        print("  修法：让 nacos 与该键的消费方一致；若各消费方取值本就不同（共享文件无法两全），")
        print("        则**从 nacos 里删掉该键**（让它回归各服务自己的代码默认值）。")
        return 1

    print("PASS: 无 [DIFF]。nacos-config/ 与各消费方非 dev 档取值等价，导入是行为等价动作。")
    if total_dev_drift:
        print(f"      另有 {total_dev_drift} 处 [DEV-DRIFT]（仅 dev 档不同，-v 查看）。")
    print("      [EXTRA] 需人工确认与 Java @Value 默认值相等（-v 查看）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
