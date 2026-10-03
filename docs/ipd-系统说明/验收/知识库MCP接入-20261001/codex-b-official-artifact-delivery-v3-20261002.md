# ARTIFACT durable attachment origin v3

PARTIAL。4temp paths/6测试实跑成功，原bin+sidecar同时丢失红例已覆盖并拒定档。main/SDK/DB/DDL未改。

六行缺口：ruoyi-ai temp；deliver_artifact→原DRAFT与原ARTIFACT；root Handle唯一seq+epoch/同事务；原事件JSON attachmentOrigin/attachment（versionId/tenant/run/sha/length/binary）；v2丢文件误legacy→durable origin判断；源码+真实文件+官方tool/Spring事务夹具A级候选。

ProjectAgentArtifactOrigin只读原 AgentRunStore.listEvents，原ARTIFACT payload保有artifactId/title/version/versionId/contentHash，增加attachmentOrigin=IPD_NATIVE_DELIVERY_V1和typed receipt，无新event/type/table。record在actual transaction才能调用；mandatory OwnedArtifactAppender归root Handle，B不调用store.append或计算生产seq。append后原event分页回读receipt精确一致，否则整事务失败。

定档guard从原event核server-issued binary，已知binary即拒文档定档，无论附件两文件存在与否；文本origin则继续核sidecar/raw bytes/SHA/content全文。没有origin但有附件文件拒；真正legacy无origin无附件仍按原文本处理，不能再由正文prefix认证binary。负例真实删bin+sidecar仍拒；事件写失败fixture同步回滚新row/event/两文件，旧版本与旧origin保留。5个原case保持，包括官方工具真实file bytes与forceappend。

## root专用callback时序

Handle.withActiveOwnership必须包住整个 target deliver transaction，保持其monitor到提交/回滚完成；tx内部写字节/receipt→DRAFT insert→row/hash回读→Origin.record→root专用publish callback（runOwned验证epoch并只writeOwned(ARTIFACT,payload)）→Origin事件回读→提交→download验证→success。该publish不可调用terminal append/finish/onComplete，不可本地算maxSeq重建第二writer。

writeOwned成功会推进seq：专用callback需保存previousSeq并注册同TX afterCompletion，非COMMITTED在Handle锁内恢复seq；否则Origin readback/commit后失败会留下内存序号错位。target文件清理也在afterCompletion；test原store内存row/event快照同回滚，真实DB/Handle callback尚待root验。

原 final finish维持原insertDraft→SUCCEEDED CAS→writeArtifactEvent→terminal。native deliver filename-hash logical artifact和final正文随机artifact分别保留原表DRAFT，无自动apply/review，也不让native交付绕过完成门。最终正文可仍产生独立DRAFT；用户展示去重如需另由root依真实产物合同裁决，不能以删最终正文或autoapply凑闭环。

## 未接线

真正Gov actor/opaque claim RuntimeAuthority、OwnedTargetFactory、root callback、API/FE、MyBatis事务和文件断电/孤儿恢复仍PENDING。fixture不代替Person/operator委托。v2旧附件两文件同时丢的误放行结论已作废。

PreparedTaskDispatch沿原codex-b-prepared-task-cold-recovery-20261002 patch/json冻结：版本CAS只保存typed Identity+phase+outcomeSHA，TrustedDeployment.authorize/rebuild必须真serverauthority；它不含已实现child ASK coldlineage，不可用UUID/session/deploymentId字符串自身授信。
