# v4 origin完整性修复

PARTIAL。G已证v3三丢/顶层versionId错+双文件丢均误放，v3legacy回落已作废。v4 4路径编译+7JUnit成功/0失败/0跳过。未改main/SDK/DB/DDL。

六行缺口：ruoyi-ai temp；原ArtifactDelivery/Guard；原Store/ARTIFACT可信来源；tenant/run/version/artifact/versionNo/hash；未知不能legacy→明确完整性失败；G真实红例+当前源码/测试A级候选。

原attachment origin找不到时，不再凭文件缺失容许。requireLegacyText读取原事件，只有同tenant/run的ARTIFACT精确versionId、artifactId、version、contentHash绑定原版本，且完整原row.content UTF8 SHA与原hash相等，才认旧纯文本。含attachmentOrigin/attachment但记录不完整不可legacy。正文前缀/JSON/UUID都无来源权威。无可信源明确Artifact trusted origin unavailable；旧源信息不足不编造迁移记录、不回避完整性失败。

新增第7测试：真实删bin+sidecar、修改origin顶层versionId为102→拒；清空origin形成三丢→拒；无事件legacy→拒；原server ARTIFACT纯文本合同的fixture精确绑定普通同prefix正文→允许；artifactId错误→拒。fixture只验证原事件消费契约，未对生产历史数据伪造/补写事件。原6测试完整保持。

Gov ExecutionClaim入口已向root/独立G协调，G确认不是其owner且无法给API；目前RuntimeAuthority必须注入，未写虚构签claim函数/假actor adapter。拿到官方writer准确typedAPI后才增加actual消费者适配，故本冻结不代表生产runtime授权接线。

root Handle唯一seq publish/事务rollback恢复seq、原final DRAFT共存时序沿v3报告；实际DB/API/FE未接。PreparedTaskCAS沿原freeze只typed调度身份与CAS receipt；child ASK lineage不因字符串/UUID或fixture自动成立。
