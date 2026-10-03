# 真实节点未知副作用最小修复

裁决：FROZEN_ISOLATED_VERIFIED，待root集成正式包。源码6路径闭包见JSON，无Store/schema/checkpoint协议/租约/SDK变更。

纠正初步归因：真实MailSend catch返回content且error默认false，邮件发送异常被当SUCCESS；此前不能声称真实邮件已重发。隔离HEAD候选替换旧Mail源码，真实MailSend.process与mock JavaMailSenderImpl抛错计数路径的断言失败（1test/1failure，“应抛但未抛”），保存原mail-before-test.log；修后显式soft error固定“邮件发送结果尚未确认，请先核对业务结果”，不返回远端异常原文。

唯一分类从原Graph四种集合移入WfComponentNameEnum.hasSideEffect，Graph与Abstract消费同一方法，不扩缩类别。副作用throw或soft失败只执行一次且无退避，白话提示核对业务结果，不报三次DEAD；其他节点原3次重试保持。

仅独立HEAD快照target运行offline module-only Maven，未拷其他WIP。修后22tests/0fail/0error/0skip：真实Mail失败一次终止/成功一次，四类副作用throw与soft一次，原只读第3次成功控制、原Graph未知副作用恢复与checkpoint失败控制。无真实邮件/HTTP/出站或DB写，main target/index/rootloader不改。依赖预存m2项目/外部artifact，不等完整HEAD reactor或生产已加载证明。JSON含冻结hash与日志目录。
