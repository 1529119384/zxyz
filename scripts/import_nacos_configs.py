import pymysql, os, glob, hashlib

conn = pymysql.connect(host='127.0.0.1', user='root', password='aD9pJ6nD6qZ7jE1wX6tQ9zI3wS0lY9aE', database='nacos', charset='utf8mb4')
cur = conn.cursor()

cur.execute('DELETE FROM config_info')

dir_path = '/tmp/nacos-config'
imported = 0
for f in sorted(glob.glob(os.path.join(dir_path, '*.yml'))):
    data_id = os.path.basename(f)
    with open(f, 'r', encoding='utf-8') as fh:
        content = fh.read()
    md5 = hashlib.md5(content.encode()).hexdigest()
    cur.execute(
        'INSERT INTO config_info (data_id, group_id, content, md5, gmt_create, gmt_modified, type) '
        'VALUES (%s, %s, %s, %s, NOW(), NOW(), %s)',
        (data_id, 'ZXYZ', content, md5, 'yaml')
    )
    imported += 1
    print(f'  OK: {data_id}')

conn.commit()
cur.close()
conn.close()
print(f'Total: {imported} configs imported')