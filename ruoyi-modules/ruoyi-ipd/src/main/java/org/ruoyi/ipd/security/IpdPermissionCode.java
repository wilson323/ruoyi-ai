package org.ruoyi.ipd.security;

/**
 * IPD 模块权限标识（SEC-01）。
 * 命名对齐 RuoYi：{@code ipd:资源:动作}。Controller 必须引用本接口常量——
 * 裸字面量散落在改名时会漏改（2026-09-06 第五批治理决议，常量化收口）。
 */
public interface IpdPermissionCode {

    String OPERATION_MODULE_PROJECT = "ipd:project:list";
    String OPERATION_MODULE_PROJECT_QUERY = "ipd:project:query";
    String OPERATION_MODULE_PROJECT_CREATE = "ipd:project:add";
    String OPERATION_MODULE_PROJECT_STATUS_CHANGE = "ipd:project:edit";

    String OPERATION_PRODUCT_GROUP = "ipd:product:list";
    String OPERATION_PRODUCT_GROUP_CREATE = "ipd:product:add";
    String OPERATION_PRODUCT_GROUP_BIND_PROJECT = "ipd:product:edit";

    String OPERATION_STAGE_ACTION = "ipd:stage-action:list";
    String OPERATION_STAGE_ACTION_EXECUTE = "ipd:stage-action:edit";
    String OPERATION_STAGE_ACTION_DELIVERABLE = "ipd:stage-action:add";

    String OPERATION_CERT_TEMPLATE = "ipd:cert-template:list";
    String OPERATION_CERT_TEMPLATE_CREATE = "ipd:cert-template:add";
    String OPERATION_CERT_TEMPLATE_DELETE = "ipd:cert-template:remove";

    String OPERATION_GATE_ELEMENT = "ipd:gate-element:list";
    String OPERATION_GATE_ELEMENT_CREATE = "ipd:gate-element:add";
    String OPERATION_GATE_ELEMENT_UPDATE = "ipd:gate-element:edit";
    String OPERATION_GATE_ELEMENT_DISABLE = "ipd:gate-element:remove";
    String OPERATION_GATE_ELEMENT_PUBLISH = "ipd:gate-element:publish";
    String OPERATION_GATE_ELEMENT_ARCHIVE = "ipd:gate-element:archive";
    String OPERATION_GATE_ELEMENT_COPY = "ipd:gate-element:copy";
    String OPERATION_GATE_ELEMENT_REVERT = "ipd:gate-element:revert";
    /** P2-5.x：归档要素恢复为草稿（archived → draft；与 copy 不同：不新建行、保留版本号） */
    String OPERATION_GATE_ELEMENT_RESTORE = "ipd:gate-element:restore";

    String OPERATION_DELETION_REQUEST_ARCHIVE = "ipd:deletion-request:archive";
    String OPERATION_DELETION_REQUEST_PURGE = "ipd:deletion-request:purge";
    String OPERATION_DELETION_REQUEST_SUBMIT = "ipd:deletion-request:submit";
    String OPERATION_DELETION_REQUEST_LEADER = "ipd:deletion-request:leader";
    String OPERATION_DELETION_REQUEST_ADMIN = "ipd:deletion-request:admin";
    /** SEC-MED-3：撤返（独立权限码 + 与 SUBMIT 解耦 + 防侧信道） */
    String OPERATION_DELETION_REQUEST_WITHDRAW = "ipd:deletion-request:withdraw";

    String OPERATION_GATE_REVIEW = "ipd:gate-review:list";

    /** AC-INC-15c：双PM 联合提议系数 */
    String OPERATION_COEFFICIENT_PROPOSE = "ipd:coefficient:propose";
    /** AC-INC-15c：产品组长确认系数 */
    String OPERATION_COEFFICIENT_CONFIRM = "ipd:coefficient:confirm";

    /** OPS-05：站内通知收件箱（本人） */
    String OPERATION_NOTIFICATION_READ = "ipd:notification:read";
    /** OPS-05：outbox 消费端手动触发（仅超管） */
    String OPERATION_NOTIFICATION_DISPATCH = "ipd:notification:dispatch";

    /** P1-10.1：AI 文档版本链读取 */
    String OPERATION_AI_DOCUMENT = "ipd:ai-document:list";
    /** P1-10.1：登记 AI 原始输出 v1（生成入口 P4-2 接管） */
    String OPERATION_AI_DOCUMENT_CREATE = "ipd:ai-document:add";
    /** P1-10.1：人工改版（基准非最新版 409） */
    String OPERATION_AI_DOCUMENT_REVISE = "ipd:ai-document:edit";
    /** P1-10.1：人工审核通过（BR-AI-03） */
    String OPERATION_AI_DOCUMENT_REVIEW = "ipd:ai-document:review";

    /** P4-2.1：AI 模型配置读（内部全员；密钥永不回显） */
    String OPERATION_AI_MODEL = "ipd:ai-model:list";
    /** P4-2.1：AI 模型配置写（仅超管） */
    String OPERATION_AI_MODEL_EDIT = "ipd:ai-model:edit";
    /** AI-P2-3（2026-09-11）：AI 副驾问答（内部全员；BR-AI-05 内部角色对等，不教 AI 编数据）。 */
    String OPERATION_AI_COPILOT = "ipd:ai-copilot:chat";

    /** SOP 模板写（仅超管） */
    String OPERATION_SOP_TEMPLATE_EDIT = "ipd:sop-template:edit";
    /** SOP 模板读（内部全员） */
    String OPERATION_SOP_TEMPLATE = "ipd:sop-template:list";

    /** P3-1.x：KPI 考核查询（内部四角色） */
    String OPERATION_KPI_QUERY = "ipd:kpi:query";

    /** P3-4.4：奖金池查询 */
    String OPERATION_BONUS_POOL_QUERY = "ipd:bonus-pool:query";
    /** P3-4.4：奖金池计算 */
    String OPERATION_BONUS_POOL_COMPUTE = "ipd:bonus-pool:compute";
    /** P3-4.4：奖金池冻结 */
    String OPERATION_BONUS_POOL_FREEZE = "ipd:bonus-pool:freeze";
    /** P3-4.4：奖金池分配 */
    String OPERATION_BONUS_POOL_DISTRIBUTE = "ipd:bonus-pool:distribute";

    /** 上市后复盘创建 */
    String OPERATION_POST_LAUNCH_REVIEW_CREATE = "ipd:post-launch-review:create";
    /** 上市后复盘完成 */
    String OPERATION_POST_LAUNCH_REVIEW_COMPLETE = "ipd:post-launch-review:complete";
    /** 上市后复盘查询 */
    String OPERATION_POST_LAUNCH_REVIEW_QUERY = "ipd:post-launch-review:query";

    /** P3-6.2：贡献度查询 */
    String OPERATION_CONTRIBUTION_QUERY = "ipd:contribution:query";
    /** P3-6.2：贡献度保存 */
    String OPERATION_CONTRIBUTION_SAVE = "ipd:contribution:save";
    /** P3-6.2：贡献度确认 */
    String OPERATION_CONTRIBUTION_CONFIRM = "ipd:contribution:confirm";

    /** P3-8.2：负反馈查询 */
    String OPERATION_NEGATIVE_FEEDBACK_QUERY = "ipd:negative-feedback:query";
    /** P3-8.2：负反馈录入 */
    String OPERATION_NEGATIVE_FEEDBACK_CREATE = "ipd:negative-feedback:create";
    /** P3-8.2：负反馈认定/解除 */
    String OPERATION_NEGATIVE_FEEDBACK_DECIDE = "ipd:negative-feedback:decide";

    /** P3-7.1：切换验收 run / get / list（内部全员可读；写操作 service 二次校验） */
    String OPERATION_SWITCHING_ACCEPTANCE_QUERY = "ipd:switching-acceptance:query";
    /**
     * P3-7.1 历史别名（lock/unlock 已于 R-NEW-SEC-5 拆为 {@code _LOCK}/{@code _UNLOCK} 并登记 catalog；本 _ADMIN 别名故意不登记）。
     * <p><b>负向契约常量·禁止删除</b>（R186 双轨收敛核实）：被 RnewPermissionContractTest 双锁——
     * {@code switchingAcceptanceAdminAliasNotRegistered}（断言任何内部角色都不持有该未登记别名，fail-closed）+
     * {@code switchingControllerDoesNotUseUnregisteredAdminAlias}（断言 Controller 源码不再引用它），
     * 防止注解层回漂到未登记别名导致连 SUPER_ADMIN 也 403 死端点。删除本常量会使两条契约测试编译失败；
     * owner 若决定正式启用别名，需手动补登 catalog 并同步改这两条契约。
     */
    String OPERATION_SWITCHING_ACCEPTANCE_ADMIN = "ipd:switching-acceptance:admin";

    /** SEC-02：审计日志 */
    String OPERATION_AUDIT_LOG_LIST = "ipd:audit-log:list";
    String OPERATION_AUDIT_LOG_VERIFY = "ipd:audit-log:verify";
    String OPERATION_AUDIT_LOG_EXPORT = "ipd:audit-log:export";

    /** AC-COMP-01/04/05：合规读 */
    String OPERATION_COMPLIANCE_READ = "ipd:compliance:read";
    /** AC-COMP-02/03：合规写 */
    String OPERATION_COMPLIANCE_WRITE = "ipd:compliance:write";

    /** 系统参数 */
    String OPERATION_SYSTEM_CONFIG_LIST = "ipd:system-config:list";
    String OPERATION_SYSTEM_CONFIG_READ = "ipd:system-config:read";
    String OPERATION_SYSTEM_CONFIG_UPDATE = "ipd:system-config:update";

    /** 产品查询 */
    String OPERATION_PRODUCT_QUERY = "ipd:product:query";


    /** P2-3.1：校验型创建招标单（MARKET_PM/GROUP_LEADER/SUPER_ADMIN） */
    String OPERATION_BID_INVITATION_CREATE = "ipd:bid-invitation:create";
    /** P2-3.3：超管指派 */
    String OPERATION_BID_INVITATION_ADMIN_ASSIGN = "ipd:bid-invitation:admin-assign";

    // ------------------------------------------------------------------
    // R-NEW-SEC-5（2026-09-07）：补齐五类业务权限码。
    // 背的缺陷不是“无鉴权”，而是“有端点无专用码”：写端点挂着 :query 码或只靠
    // requireInternal()，导致权限目录无法审计、也无法按操作收紧。
    // 统一口径：注解层只把住“此类操作可被哪一类内部角色调”，
    // 对象级（项目成员/双签人/申请人）仍由 service 的 IpdIdorGuard 二次校验，
    // 与既有 negative-feedback / contribution 系列完全同款。
    // ------------------------------------------------------------------

    // （G5 上市 90 天复盘三码已在上方 P2-5.6 段定义：POST_LAUNCH_REVIEW_QUERY/CREATE/COMPLETE，
    //  合并去重：本地 R-NEW 段重复声明已删，避免同值常量双声明编译错）

    /** 二、P3-1.2：共担 KPI 双组长确认签署（第一/第二签同码，同人重复签由 service 拒） */
    String OPERATION_KPI_SHARED_CONFIRM_SIGN = "ipd:kpi-shared:confirm";
    /** 三、P3-1.1：共担 KPI 归集执行（写 kpi_records，不应继续挂在 ipd:kpi:query 上） */
    String OPERATION_KPI_SHARED_COLLECT = "ipd:kpi-shared:collect";

    /** 四、P1-x：需求变更单提交/状态流转（旧沿用通用 project-status-change 码） */
    String OPERATION_REQUIREMENT_CHANGE_SUBMIT = "ipd:requirement-change:submit";
    /** 四、需求变更单签收（双签的另一签；签收人不等于提交人由 service 校） */
    String OPERATION_REQUIREMENT_CHANGE_SIGN = "ipd:requirement-change:sign";

    /** 五、P3-7.1：切换验收月结锁定（从现有 :admin 拆细，使解锁可单独收敛） */
    String OPERATION_SWITCHING_ACCEPTANCE_LOCK = "ipd:switching-acceptance:lock";
    /** 五、P3-7.1：切换验收月结解锁（仅超管；:admin 作为历史别名保留，不破坏现有注解） */
    String OPERATION_SWITCHING_ACCEPTANCE_UNLOCK = "ipd:switching-acceptance:unlock";

    // ------------------------------------------------------------------
    // R149 batch2a 后端 DDL 改造配套权限码（2026-09-20）
    // 缺口卡点：8 项 KPI 仅窗口命中率有算法、7 项无录入入口；
    //          落地场景完全无登记入口；90 日回款预警完全无实现。
    // 设计：写操作独立成码（不挂在 :query 上），符合 R-NEW-SEC-5 治理。
    // ------------------------------------------------------------------

    /** R149-A2：KPI 原始数据录入（GROUP_LEADER / SUPER_ADMIN，按月录入） */
    String OPERATION_KPI_RAW_CREATE = "ipd:kpi:raw:create";
    /** R149-A2：KPI 原始数据查询（内部四角色可读） */
    String OPERATION_KPI_RAW_QUERY = "ipd:kpi:raw:query";

    /** R149-A4：落地场景登记（MARKET_PM / RD_PM / GROUP_LEADER / SUPER_ADMIN） */
    String OPERATION_SCENARIO_LANDED_CREATE = "ipd:scenario:landed:create";
    /** R149-A4：落地场景查询（内部四角色） */
    String OPERATION_SCENARIO_LANDED_QUERY = "ipd:scenario:landed:query";

    /** R149-C1：90 日回款预警扫描触发（GROUP_LEADER / SUPER_ADMIN） */
    String OPERATION_RECOVERY_CHECK_90D = "ipd:recovery:check-90d";
    /** R149-C1：90 日回款预警列表查询（内部四角色） */
    String OPERATION_RECOVERY_WARNINGS_QUERY = "ipd:recovery:warnings:query";

    // ------------------------------------------------------------------
    // R149 batch2b：A5/C3/C4 新增权限码（2026-09-20）
    // ------------------------------------------------------------------

    /** A5：业务参数读（按 scope 维度；内部全员；AC-A5 治理配置可视） */
    String OPERATION_BUSINESS_CONFIG_READ = "ipd:business-config:read";
    /** A5：业务参数写（仅组长/超管；AC-A5 治理参数变更受控） */
    String OPERATION_BUSINESS_CONFIG_WRITE = "ipd:business-config:write";

    /** C3：永久清除（仅超管；二次确认 + 审计；AC-C3 数据治理底座） */
    String OPERATION_PERMANENT_DELETE = "ipd:permanent-delete:execute";

    // ------------------------------------------------------------------
    // R215 权限可配置化（owner 指令 2026-09-24）：角色→权限码运行时配置（元权限）
    // 自举设计：本域码不参与 DB 配置自身（防止把管理员锁在门外），永远登记在 ADMIN_WRITE 仅超管。
    // ------------------------------------------------------------------

    /** 角色权限配置：查看目录/覆盖行/有效快照（仅 SUPER_ADMIN） */
    String OPERATION_ROLE_PERMISSION_CONFIG_QUERY = "ipd:role-permission:query";
    /** 角色权限配置：增删改覆盖行 + reload（仅 SUPER_ADMIN；变更留 remark 依据 + 审计） */
    String OPERATION_ROLE_PERMISSION_CONFIG_EDIT = "ipd:role-permission:edit";

    /** C4：P0 升级链查询（组长/超管） */
    String OPERATION_P0_ESCALATION_READ = "ipd:p0-escalation:read";

    // ------------------------------------------------------------------
    // W2-KPI2A（2026-09-21）：A2 P1 功能指标量表配套权限码
    // 授权：OWNER-拍板登记-20260921.md（P3 九项全部完整执行 → 第 2 项）
    // 语义：写码（录入/更新/软删）与读码分离，符合 R-NEW-SEC-5「写操作不挂 :query」。
    // ------------------------------------------------------------------

    /** A2 P1：功能指标量表录入 / 更新 / 软删（SUPER_ADMIN / MARKET_PM / RD_PM） */
    String OPERATION_KPI_CONFIG = "ipd:kpi:config";
    /** A2 P1：功能指标量表查询（内部四角色可读） */
    String OPERATION_KPI_CONFIG_QUERY = "ipd:kpi:config:query";
}
