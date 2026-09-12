"""把 nacos-config/*.yml 一次性导入 Nacos 的 config_info 表。

用法（连接参数全部走环境变量，脚本内不再保存任何口令）：
    export NACOS_DB_PASSWORD=<口令>
    python3 scripts/import_nacos_configs.py
    （可选覆盖：NACOS_DB_HOST / NACOS_DB_USER / NACOS_DB_NAME / NACOS_CONFIG_DIR）

背景：本脚本原先把 MySQL 口令硬编码在第 3 行，且该文件被 .gitleaks.toml 按「整文件路径」
豁免，于是真实口令入库却始终不被门禁发现（2026-09-13 审计 12-P0-1 复核确认，该值即线上
MYSQL_ROOT_PASSWORD / CONFIG_DB_PASSWORD）。现改为环境变量传参，并已把豁免收窄到历史提交。
"""
import glob
import hashlib
import os
import sys

import pymysql

DIR_PATH = os.environ.get('NACOS_CONFIG_DIR', '/tmp/nacos-config')
GROUP_ID = 'ZXYZ'

host = os.environ.get('NACOS_DB_HOST', '127.0.0.1')
user = os.environ.get('NACOS_DB_USER', 'root')
password = os.environ.get('NACOS_DB_PASSWORD', '')
database = os.environ.get('NACOS_DB_NAME', 'nacos')

if not password:
    sys.exit(
        '缺少 NACOS_DB_PASSWORD：请通过环境变量传入数据库口令。'
        '不要把口令写回脚本——本文件历史上曾因此泄露过真实口令。'
    )

conn = pymysql.connect(host=host, user=user, password=password,
                       database=database, charset='utf8mb4')
cur = conn.cursor()

cur.execute('DELETE FROM config_info')

imported = 0
for f in sorted(glob.glob(os.path.join(DIR_PATH, '*.yml'))):
    data_id = os.path.basename(f)
    with open(f, 'r', encoding='utf-8') as fh:
        content = fh.read()
    md5 = hashlib.md5(content.encode()).hexdigest()
    cur.execute(
        'INSERT INTO config_info (data_id, group_id, content, md5, gmt_create, gmt_modified, type) '
        'VALUES (%s, %s, %s, %s, NOW(), NOW(), %s)',
        (data_id, GROUP_ID, content, md5, 'yaml')
    )
    imported += 1
    print(f'  OK: {data_id}')

conn.commit()
cur.close()
conn.close()
print(f'Total: {imported} configs imported')
