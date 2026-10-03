# 官方 ArtifactDeliveryTarget → 原 DRAFT 候选

裁决 PARTIAL。仅两新temp文件，未改main/SDK/pom/数据库。

六行缺口：
- 仓库：ruoyi-ai；B service-e972 temp target/test。
- 入口：官方 ArtifactDeliveryTool→ArtifactDeliveryTarget；原 run-owner 对应 target Binding。
- 边界：原 ArtifactVersionStore/TransactionTemplate/IpdCopilotAccess，RunOwnerTransaction 必须接 Handle.withActiveOwnership。
- 表：原 ipd_agent_artifact_version，content MEDIUMTEXT NOT NULL，无 binary列；原 ai_documents不新增轨。
- 现状→目标：onArtifact仅事件；新target先真实字节落盘+原DRAFT版本回读SHA才返回success。
- 证据：A级源码/实际SDK consumer/真实临时文件+Spring事务夹具；不冒DB或运行态验收。

4/4测试通过。官方 pinned204 ArtifactDeliveryTool 从真实 LocalFilesystem 读取任意0/255字节，转原目标；全文UTF8保留（中文/emoji/4000字不截断），原raw bytes按雪花versionId.bin存于仅host控制的canonical root，FileChannel.force(true)，下载验证原row tenant/run+当前member/Personowner和SHA。binary只存固定typed receipt元信息，不base64内容，status始终DRAFT/documentId空；target不调用onComplete/onArtifact/markApplied/apply/review。

force=false同logical filename冲突；force=true同artifact追加versionNo保留旧row和旧bytes。所有权丢失/crossrun-owner/childsession/当前撤权拒绝；字节篡改不返回可验证下载。真实Spring afterCompletion在强制commit失败回滚时移除本次文件，内存store夹具恢复原row集合。此回滚不是MyBatis/真实数据库结果。首轮3fail因Mac /var祖先symlink，测试改为传canonical临时root，生产祖先防护未放宽；javac首轮转义失败原log保留。

## 必须同闭包整合

1. 将server-created Binding和生产 Handle.withActiveOwnership 映射RunOwnerTransaction；真实TransactionTemplate用原数据库事务manager。不得采用默许owner lambda作为正式接线。
2. 附 mandatory-apply-guard.patch 为当前RunService.applyArtifact行锁之后、requireArchiveDocType之前加入requireDocumentContent(seen)。binary元信息没有此guard会被现apply当正文写ai_documents，因此target不能单独上线。该hunk只生成未改main，whole RunService未编译。
3. 原Controller窄扩 GET /api/v1/agent-runs/{runId}/artifacts/versions/{versionId}/download：从真实Person actor+run own读取创建授权download target，返回application/octet-stream/Content-Disposition attachment（不能把bytes包装成String/Base64 code0正文），不暴露host路径。下载只读授权不要求run仍执行；target.download每次核当前member/rowtenant/run/SHAs。此endpoint未实现，当前typed方法是待接窄端口。
4. 原ArtifactView/ARTIFACT事件需给type/download版本信息；FE binary只显示下载/禁文档定档，文本仍全文preview原apply。不得把binary receipt塞进业务正文渲染。本target尚未发ARTIFACT事件，root需在同原事务/seq所有权下接，不能新event轨。

不完整项：生产storageRoot/权限、真实MyBatis rollback、API/FE和run事件接线；崩溃/写失败同步注册前可能孤儿文件，需只核无row的owned version文件恢复清理，不能靠全目录删除。FileChannel.force不证明目录元信息断电持久性。reserved receipt prefix与文档正文语义需正式contract测试（当前prefix guard可能把同prefix普通文本拒定档）；当前候选不声明全binary业务归档完成。
