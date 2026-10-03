# D 权限最小修补

状态：PENDING_VALIDATION。仅源码与测试变更；未运行 Maven、未写主target、未加载16039。

## 六行缺口

- 仓库：ruoyi-ai。
- 入口：StageAcceptanceService.submitStage/assertApprover、ProjectService.advanceStage。
- 服务边界：复用IpdIdorGuard.requireProjectMemberOrSuperAdmin，不改变角色模块权限与Gate。
- 表字段：只读project_members(project_id/person_id/exit_date)、projects.product_line_id、product_lines.leader_person_id，无DDL。
- 现状目标：跨组守卫与空阶段报错没有防住同组非成员阶段写；添加成员或超管守卫，负责人优先从project.productLineId获取，无该字段再兼容products关联。
- 证据等级：A源码与diff检查；测试源码已增加，执行待A统一Maven；运行态待A统一候选包。

修改四文件：StageAcceptanceService.java、ProjectService.java、StageAcceptanceServiceTest.java、ProjectServiceTest.java，均在原有目录。before SHA保存codex-d-permission-before-20261002.json。git diff --check退出0。

守卫保持已有超管例外，但已有负责人时超管仍不能代批；提交人不能自批；非超管缺成员Mapper时fail-closed。不增加第二套权限服务。测试新增同组GROUP_LEADER非成员推进零更新、非成员提交/批准零更新、项目直接产品线且无产品时负责人审批及超管不可代批。原成员正例补mock成员行，不降低现有业务断言。

真实负例建议：A统一新包加载后，以同组负责人非项目成员+已有当前阶段+其他Gate前置满足的停用QA fixture，分别调用stage-acceptance/advance-stage，期望403/code30001并回读项目阶段/状态/审计前后不变。另用项目有productLineId但productId=null检查已有负责人仍不可由超管代批。不要改真实项目或用空阶段400代替权限结果。

注意：StageAcceptanceService原小阶段同人批准幂等分支不写数据，本次未改其幂等合同。G修改应由A/B独立复核，未自授最终业务验收。

## A 首次编译失败后纠正

A 原始编译报错：StageAcceptanceService.java:245，Project.getProductLineId不存在。G此前把计划关系误当作当前实体字段，此推断错误。已撤回负责人直接产品线变更和不存在setter测试，恢复原productId→products.productLineId→product_lines查询；仅保留实存成员守卫和非成员零写测试。没有加虚字段、DDL或改权限合同。负责人无产品场景仍待关系合同与实库查证，不宣称修复。四文件冻结交A重新测试。
