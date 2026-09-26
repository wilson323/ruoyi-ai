import sys; sys.path.insert(0, ".")
import r219_lib as L
print("== persons cols =="); print([r[0] for r in L.sql("SHOW COLUMNS FROM persons")])
print("== app db users ==")
for r in L.sql("SELECT user, host FROM mysql.user"): print(r)
print("== grants ipd_app ==")
try:
    for r in L.sql("SHOW GRANTS FOR 'ipd_app'@'%'"): print(r)
except Exception as e: print("ERR", e)
try:
    for r in L.sql("SHOW GRANTS FOR 'ipd_app'@'localhost'"): print(r)
except Exception as e: print("ERR", e)
print("== persons sample ==")
for r in L.sql("SELECT id, name, role_code, group_id, level FROM persons LIMIT 12"): print(r)
