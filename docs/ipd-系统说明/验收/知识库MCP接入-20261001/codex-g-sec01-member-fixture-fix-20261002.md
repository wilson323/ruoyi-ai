# G SEC01合法动作正例夹具补全

六行缺口（编辑前源已读，初报告误落temp路径未成功，现补证）：
1. 仓库ruoyi-ai；仅security/Sec01AcceptanceTest.java授权测试路径。
2. stageActionTransitOperatorComesFromSession调用真实Controller→真实Permission→mock StageActionService。
3. 原Permission没有required mapper注入；原合法动作没有projectId，fixture无法走成员守卫。
4. 真实规则需projects.id/tenant_id及project_members在任；现mapper mock合法成员查询。
5. 补projectId/tenant/member，保留原controller/operator会话身份断言，不放宽负例或改production。
6. 主线全量3752/1error及源A级；隔离HEAD72+Permission两freeze文件+该test，仅独立target。

before test13e51b41a6aa66097ced27746d7dc0ab40f24d366da08dc2c02dcc25b1fc0c9d。

原正例显式注入ProjectMapper/ProjectMemberMapper，项目11/tenant000000及count1现成员；动作project11，operator断言原样。初执行工具workdir为temp，故先仅temptest成功修改，证据文件相对路径不存在；现相同最小补丁写授权主test并补证，main target未运行。production/test freeze两文件未改。

隔离先Sec01+Action22/0/0/0通过，再同一HEAD72三路径快照全五类48/0/0/0通过：Sec0113、Action9、Registry6、PasswordScope7、HEADStage13。Sec01相对HEAD72唯一正例局部+6行，其余无WIP差异；三path临时与现源码hash全一致。证据codex-g-member-three-paths-isolated-20261002.json，log/tmp/ipd-g-member-three-paths-48-tests-20261002.log。source frozen，未主target/index/commit操作。
