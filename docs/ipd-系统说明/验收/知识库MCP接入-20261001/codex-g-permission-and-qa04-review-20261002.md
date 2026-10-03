# G 权限与 QA04 独立复核

裁决：权限负例切片闭环，其余验收PARTIAL。只读原证据与现源码/配置键，不写QA、账号、权限、密码或Schema。

codex-d-real-samegroup-nonmember-20261002.json的auth/me Person为900102/GROUP_LEADER/group900001。真实HTTP两个写入口advance-stage与stage-acceptance均403/code30001「非项目成员，无权访问」，不是旧空阶段400；现源码成员守卫确在阶段查找和业务写之前，因此validStageCount0不削弱该成员守卫拒绝结论，但不能扩为已有有效阶段/动作批准整链验收。原QA项目非成员fixture、同组临时改值及finally恢复需以B脚本/执行记录结合本JSON，不从原backup跨组值推出当时仍跨组。

正确文档只读接口是文档列表：positiveDocumentList HTTP200/code0且包含2106072675242741762；revokedDocumentList为403。旧/ai-documents/{id}404是错误路由，不能当权限证据。两写拒绝前后SHA相同；first restoredExact=true且restoredSha=originalSha；第二轮secondRestoredExactChangedFields=true。没有新增阶段/动作，validStageCount0及limitation明确记录。

QA04已有安全读取线索：.codex/ipd-dev/config/mysql-client.cnf支持原business-race-preflight的13306 ipd_qa04只读schema查询；还存在p142-test-database.json、p053-integrated-test-database.json、auth-test-database.json、audit-test-database.json，键包含jdbcUrl/username/password，但均未引用ipd_qa04，不能臆造为本次四并发环境。未打印值或凭据。

原business-race-preflight-20261002.json已证明QA04在2026-10-02T15:01:26Z缺projects/products.product_line_id、整张product_lines与ai_documents.review_comment，不只是凭据问题。读取路线存在不等于当前隔离库合同兼容；未经结构授权不迁移或修改。因此剩余四并发测试仍未验，不能拿dev库或其他测试库凭据静默替代QA04。A若有已授权兼容隔离库，应显式核四测试实际环境键与库schema后单独执行；本轮不改测试环境。

当前Qa04MysqlConcurrencyTest固定用户qa04_runner、库ipd_qa04，读取qa04.db.password系统属性或QA04_DB_PASSWORD环境。当前环境变量未设置，现配置目录没有qa04_runner/QA04_DB_PASSWORD入口匹配；不能拿root cnf绕过测试固定权限合同。四并发仍未验。
