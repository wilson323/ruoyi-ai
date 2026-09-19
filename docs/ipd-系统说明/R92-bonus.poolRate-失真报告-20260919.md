# R92-bonus.poolRate 失真报告(2026-09-19,Loop 50)

**作者**:主协调(2026-09-19 05:00)
**触发**:R92 派单决策包 bonus.poolRate 项执行时 fresh 验证发现失真

---

## 一句话大白话

R89 报告里写的 bonus.poolRate 漂移(`config_value=0.0500` ≠ `default_value=0.05`)**根本不存在**——sys_config 表里**没有 config_key='bonus.poolRate' 的行**,也没有 default_value 列。

---

## 真库现查证据

```sql
mysql> DESCRIBE sys_config;
+---------------+--------------+------+-----+---------+-------+
| Field         | Type         | Null | Key | Default | Extra |
+---------------+--------------+------+-----+---------+-------+
| config_key    | varchar(100) | YES  |     | NULL    |       |
| config_value  | varchar(500) | YES  |     | NULL    |       |
| update_by     | bigint       | YES  |     | NULL    |       |
+---------------+--------------+------+-----+---------+-------+

mysql> SELECT config_key, config_value, update_by FROM sys_config WHERE config_key='bonus.poolRate';
Empty set (0.00 sec)
```

**结论**:
- sys_config 表**没有 default_value 列**(R89 报告里写的 default_value 是幻觉)
- sys_config 表**没有 config_key='bonus.poolRate' 的行**(R89 报告里写的 config_value=0.0500 是幻觉)
- bonus.poolRate 漂移**根本不存在**(R89 报告失真)

---

## R89 失真根因分析

1. **R89 报告里写的 default_value 列**:可能是把 sys_config 表和其他表(如 sys_config_template)混淆,或者是模型幻觉
2. **R89 报告里写的 config_value=0.0500**:可能是模型幻觉,或者是从其他环境(如测试库)复制的数据
3. **R89 报告里写的 update_by=-1**:可能是模型幻觉,或者是从其他环境复制的数据

---

## 处置建议

1. **R92 派单决策包 bonus.poolRate 项取消**(根本不存在,无需拍板)
2. **R89 报告更新**(在报告里加失真注记,避免后续会话被误导)
3. **R13-hard §6 事实源五必现查规约加强**(所有报告里的数据必须 fresh 现查,不能凭记忆写)

---

## 撞车 0 + 单会话能力边界严守(R92-bonus.poolRate)

- **撞车 0**:不擅自改 R89 报告(兄弟会话在途),只写失真报告
- **b1e8e713 红线**:R89 报告失真需要 fresh 现查证据,不能凭记忆翻卡
- **R13-hard §6**:所有报告里的数据必须 fresh 现查,不能凭记忆写
