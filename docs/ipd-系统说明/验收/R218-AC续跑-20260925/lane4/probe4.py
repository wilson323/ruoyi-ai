import sys; sys.path.insert(0, ".")
import r219_lib as L
print("== groups cols =="); print([r[0] for r in L.sql("SHOW COLUMNS FROM product_groups")])
for r in L.sql("SELECT * FROM product_groups LIMIT 3"): print(r)
print("== configs ==")
for r in L.sql("SELECT config_key, config_value FROM system_configs WHERE config_key LIKE 'bonus.%' OR config_key LIKE 'allowance.%' OR config_key LIKE 'contribution.%' ORDER BY config_key"): print(r)
print("== ai_model_configs ==")
for r in L.sql("SELECT * FROM ai_model_configs LIMIT 5"): print(r)
print("== projects ==")
for r in L.sql("SELECT id, name, level, COALESCE(level_coefficient,'NULL'), target_sales_amount, launch_date, current_stage, status FROM projects WHERE del_flag='0' ORDER BY id DESC LIMIT 6"): print(r)
print("== business_config keys ==")
try:
    for r in L.sql("SELECT config_key, config_value, status FROM ipd_business_config LIMIT 20"): print(r)
except Exception as e: print("ERR", e)
print("== login ==")
for u in ["ipd-admin","ipd-leader","ipd-market","ipd-rd"]:
    print(u, bool(L.login(L.B39, u)))
