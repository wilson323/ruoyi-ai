# Pointer #120 — additional-location-mandatory（R-2 根因）

> **类型**：反思层（根因型）
> **创建时间**：2026-09-20（R132 补落）
> **基线**：R129 §三.1 R-2 实证
> **严重度**：🔴
> **触发**：后端启动未带 `--spring.config.additional-location` 连错库
> **修复路径**：`scripts/check-backend-config-location.sh`（H-1 新脚本，1 hr）

## Why（为何需要）
R97 commit `f269dbc7` PID 70295 后端实测连错库（ry-vue 而非 ipd_dev）。R129 §三.1 R-2 列为 0/16 致命盲区。后端启脚本必须强制 `--spring.config.additional-location=$CONF_DIR/`。

## How（如何触发）
```bash
bash scripts/check-backend-config-location.sh
# 注入不带 additional-location 的 jvm args → 应 exit ≠ 0
```

## Link（关联）
- #125 additional-location-evolve（演化策略）
- R129 §三.1 R-2

## 自证能红
故意启动 jar 不带 --spring.config.additional-location → 跑 → exit ≠ 0
