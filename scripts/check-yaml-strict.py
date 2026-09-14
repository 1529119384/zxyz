#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""严格 YAML 校验：同时抓「语法错误」与「重复键」。

为什么需要这个脚本
------------------
Spring Boot 用 **SnakeYAML** 解析 ``application*.yml`` / Nacos 配置，
而 SnakeYAML **把重复键视为致命错误** —— 命中的服务会**直接启动失败**
（本仓真实踩过：给 ``application-common.yml`` 新增日志配置时产生了重复的顶层
``logging:`` 键，导致 9 个服务起不来）。

PyYAML 的 ``yaml.safe_load`` 默认「后者覆盖前者」，**看不出重复键**，
所以本脚本用一个自定义 Loader（``StrictLoader``）在构造 mapping 时显式判重。

用法
----
    python3 scripts/check-yaml-strict.py          # 校验全部（CI 用）
    python3 scripts/check-yaml-strict.py -v       # 额外打印每个通过的文件

CI 集成
-------
由 ``.github/workflows/ci-cd.yml`` 的 **lint-config** 作业调用
（审计 11 §7.12 / P2-13：``scripts/**``、``sql/**`` 此前完全不在 CI 覆盖内）。
退出码：0 = 全部通过；1 = 有文件失败；2 = 环境问题（缺 pyyaml / 非 git 仓库）。
"""

import subprocess
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    print('ERROR: 需要 pyyaml（CI 中由 lint-config 作业先行安装）', file=sys.stderr)
    sys.exit(2)

# 排除前端子仓（有自己的工具链与配置格式）与构建产物。
EXCLUDE_PREFIXES = ('ZXYZdatabaseFront/', '.qoder/')
EXCLUDE_PARTS = ('/target/',)


class StrictLoader(yaml.SafeLoader):
    """与 SafeLoader 等价，但**拒绝重复键**。"""


def _construct_mapping(loader, node, deep=False):
    # flatten_mapping：先展开 ``<<`` merge key，避免把 merge 误判为重复。
    loader.flatten_mapping(node)
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        try:
            duplicate = key in mapping
        except TypeError:
            duplicate = False  # 不可哈希的键：交给后续解析自然报错
        if duplicate:
            raise yaml.constructor.ConstructorError(
                'while constructing a mapping', node.start_mark,
                'found duplicate key: %r' % (key,), key_node.start_mark)
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


StrictLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping)


def _list_files():
    try:
        # -z：NUL 分隔且**不做路径转义** —— 否则中文路径会变成 \344\272\221 这类
        #    八进制转义（core.quotepath 默认行为），既不可读也无法 open。
        out = subprocess.run(
            ['git', 'ls-files', '-z', '*.yml', '*.yaml'],
            check=True, capture_output=True, text=True).stdout
    except Exception as exc:  # pragma: no cover
        print('ERROR: 无法执行 git ls-files（需在 git 仓库根目录运行）: %s' % exc,
              file=sys.stderr)
        sys.exit(2)

    files = []
    for line in out.split('\0'):
        name = line.strip()
        if not name:
            continue
        if name.startswith(EXCLUDE_PREFIXES):
            continue
        if any(part in name for part in EXCLUDE_PARTS):
            continue
        files.append(name)
    return files


def _check(path):
    """返回错误字符串列表（空列表 = 通过）。"""
    try:
        with open(path, 'r', encoding='utf-8') as handle:
            list(yaml.load_all(handle, Loader=StrictLoader))
    except yaml.YAMLError as exc:
        return [str(exc).strip().replace('\n', ' | ')]
    except UnicodeDecodeError as exc:
        return ['无法按 UTF-8 解码: %s' % exc]
    except FileNotFoundError:
        return ['索引中存在但工作区缺失（需 git add/restore 同步索引与工作区）']
    return []


def main():
    verbose = any(a in ('-v', '--verbose') for a in sys.argv[1:])
    files = _list_files()
    failed = []

    for path in files:
        errors = _check(path)
        if errors:
            failed.append((path, errors))
            for err in errors:
                # GitHub Actions 注解格式：失败原因直接挂到文件上
                print('::error file=%s::%s' % (path, err))
        elif verbose:
            print('[OK] %s' % path)

    print('')
    print('=' * 70)
    print('严格 YAML 校验：共 %d 个文件，失败 %d 个' % (len(files), len(failed)))
    if failed:
        print('')
        print('失败清单：')
        for path, errors in failed:
            print('  - %s' % path)
            for err in errors:
                print('      %s' % err[:200])
        print('')
        print('提示：重复键在 SnakeYAML（Spring Boot 使用）下是致命错误，')
        print('      会让服务**直接启动失败**。请合并或删除重复的键。')
        return 1

    print('PASS：没有重复键，也没有语法错误。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
