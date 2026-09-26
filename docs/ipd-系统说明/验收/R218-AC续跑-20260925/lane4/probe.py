#!/usr/bin/env python3
# lane4 侦察探针：只读，摸清账号/配置/表结构/项目底数
import sys, json
sys.path.insert(0, ".")
import r219_lib as L

print("== mysql user =="); print(L.sql("SELECT CURRENT_USER(), DATABASE()"))
print("== grants =="); 
try: print(L.sql("SHOW GRANTS FOR CURRENT_USER()"))
except Exception as e: print("ERR", e)
print("== audit_logs columns =="); print([r[0] for r in L.sql("SHOW COLUMNS FROM audit_logs")])
print("== persons (roles) =="); 
for r in L.sql("SELECT id, name, role, group_id, level FROM persons WHERE role IS NOT NULL LIMIT 20"): print(r)
print("== system_configs bonus/allowance ==")
for r in L.sql("SELECT config_key, config_value FROM system_configs WHERE config_key LIKE 'bonus.%' OR config_key LIKE 'allowance.%' ORDER BY config_key"): print(r)
print("== projects sample ==")
for r in L.sql("SELECT id, name, level, level_coefficient, target_sales_amount, launch_date, current_stage, status FROM projects ORDER BY id DESC LIMIT 8"): print(r)
print("== ai_model_configs ==")
for r in L.sql("SELECT id, name, provider, endpoint, model, is_default, is_enabled FROM ai_model_configs LIMIT 10"): print(r)
print("== roles distinct =="); print(L.sql("SELECT DISTINCT role FROM persons"))
print("== login probe ==")
for u in ["ipd-admin","ipd-leader","ipd-market","ipd-rd"]:
    t = L.login(L.B39, u)
    print(u, "token:", bool(t))
