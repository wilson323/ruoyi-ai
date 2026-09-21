# R152 C 业务回归 — 场景 A：组长录入 KPI 原始数据

**文档版本**：v1.0 · 2026-09-21
**对应接口**：`POST /api/v1/kpi/raw-records` · `GET /api/v1/kpi/raw-records`
**对应真库表**：`kpi_raw_records`（R149 batch2a 新建，B3 已加 `create_dept` 列）
**适用角色**：产品组长（`ipd-leader` / `Ipd@123456`）+ 超管（`ipd-admin` / `Ipd@123456`）
**对应前端页**：`/ipd/performance/raw-records`（运营管理 → 绩效管理 → KPI 原始数据）

---

## 1. 业务方操作步骤（按截图定位）

| 步 | UI 区域 | 操作 |
|---|---|---|
| 1 | 浏览器地址栏 | 打开 `http://127.0.0.1:15800/login`（或兄弟前端 `http://127.0.0.1:15666/login`） |
| 2 | 登录框 | 输入 `ipd-leader` / `Ipd@123456`，点击登录 |
| 3 | 左侧菜单 | 展开 **「运营管理」** → **「绩效管理」** → 点击 **「KPI 原始数据」** |
| 4 | 顶部表单 | 「项目」下拉选 `1001`（或任意已有项目） |
| 5 | 顶部表单 | 「KPI 类型」下拉选 `需求准确率 (REQUIREMENT_ACCURACY)` |
| 6 | 顶部表单 | 「录入期间」日期框选 `2026-09-01`（按月，按月首日） |
| 7 | 顶部表单 | 「原始值」输入框填 `0.9525`（需求准确率；小数 0-1） |
| 8 | 顶部表单 | 点击 **「保存」** 按钮 |
| 9 | 表格区 | 新增一行：`2026-09 / REQUIREMENT_ACCURACY / 0.9525` |
| 10 | 顶部 toast | 弹出 **「保存成功」** 绿色提示 |

> 截图位：步骤 9 的表格第 1 列 `id`（雪花算法 19 位）；步骤 10 的 toast 3 秒后自动消失。

---

## 2. 期望 HTTP 响应

### 2.1 正常路径（HTTP 200）

```bash
curl -sS -X POST http://127.0.0.1:16040/api/v1/kpi/raw-records \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "kpiType": "REQUIREMENT_ACCURACY",
    "projectId": 1001,
    "recordPeriod": "2026-09-01",
    "rawValue": 0.9525
  }'
```

**响应体**（实测于 2026-09-21 / 16040 backend）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": "2101934706252640258",
    "kpiType": "REQUIREMENT_ACCURACY",
    "projectId": "1001",
    "recordPeriod": [2026, 9, 1],
    "rawValue": 0.9525,
    "recordedBy": "900101",
    "recordedAt": 1789975260686,
    "createTime": 1789975260686,
    "createBy": "-1"
  },
  "timestamp": "2026-09-21T07:21:00Z",
  "traceId": "53ee2f46e6af4623b2e58cc5a7c373b1"
}
```

### 2.2 异常路径

| 异常 | HTTP | code | message |
|---|---|---|---|
| 缺 `kpiType` | 400 | 10001 | `kpiType 不能为空` |
| 缺 `projectId` | 400 | 10001 | `projectId 不能为空` |
| `rawValue` 为负 | 400 | 10001 | `rawValue 不能为负数` |
| 未带 Token | 401 | 20001 | `未认证或凭证失效` |
| 角色无 `ipd:kpi:raw:create` 权限 | 403 | 20003 | `无操作权限` |

**实测 400（缺 `kpiType`）**：

```bash
curl -X POST .../api/v1/kpi/raw-records -d '{"projectId":1001,"recordPeriod":"2026-09-01","rawValue":0.5}'
→ {"code":10001,"message":"kpiType 不能为空",...}
```

---

## 3. 真库验证 SQL

```sql
-- 单条核验（步骤 9 的表格第 1 列）
SELECT id, kpi_type, project_id, record_period, raw_value, recorded_by, tenant_id, del_flag
  FROM kpi_raw_records
 WHERE id = 2101934706252640258;

-- 期望：
-- id=2101934706252640258 | kpi_type=REQUIREMENT_ACCURACY | project_id=1001
-- record_period=2026-09-01 | raw_value=0.9525 | recorded_by=900101
-- tenant_id=000000 | del_flag=0

-- 列表核验（步骤 9 表格应见 1 行）
SELECT COUNT(*) FROM kpi_raw_records
 WHERE kpi_type='REQUIREMENT_ACCURACY' AND project_id=1001 AND del_flag=0;
```

---

## 4. curl 实测记录（2026-09-21）

```text
$ curl ... -d '{"kpiType":"REQUIREMENT_ACCURACY","projectId":1001,"recordPeriod":"2026-09-01","rawValue":0.9525}'
HTTP 200 · 73ms · data.id=2101934706252640258

$ curl ... -d '{"projectId":1001,"recordPeriod":"2026-09-01","rawValue":0.5}'  # 缺 kpiType
HTTP 400 · 18ms · code=10001 · "kpiType 不能为空"

$ curl ...  # 无 Token
HTTP 401 · 2ms · code=20001 · "未认证或凭证失效"

$ curl ... -X GET '?projectId=1001&kpiType=REQUIREMENT_ACCURACY'
HTTP 200 · 27ms · data[0].id=2101934706252640258
```

---

## 5. 业务方反馈表

| 字段 | 期望 | 业务方填 |
| --- | --- | --- |
| 录入耗时 | < 30 秒 | |
| 异常提示是否清晰 | 是（`kpiType 不能为空` 等字段级提示） | |
| 字段含义是否明确 | 是（8 项 KPI 类型下拉均带说明） | |
| 流程是否完整 | 是（选项目→选类型→填期间→填值→保存→表格刷新） | |
| 改进建议 | | |
