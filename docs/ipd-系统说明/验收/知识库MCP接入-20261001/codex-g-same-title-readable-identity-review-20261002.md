# G 同名来源白话编号独立反例

裁决：CONFIRMED_PARSER_GAP，仅现源码纯本地反例。当前 Gate SHA-256：496644da74dfc0c9e9eb05d478f2308442b5b0412dc6754b9fe8cdad6693fe98。源码复制到独立 temp 后 javac0/probe0，无网络/模型/DB写/source/index/主target改动。

受控 SOURCE 同时含同名“同名资料.md”两条：kb-12 是知识片段、未审核项目文档；doc-34 是正式项目文档、REVIEWED。citationText无小数/百分数，排除数值分支遮蔽。实际结果：

- “同名资料.md（资料编号：kb-12）来自项目已审核文档”：允许，错误借身份。
- “同名资料.md（资料编号：unknown-56）来自项目已审核文档”：允许，未知编号无权威身份仍被放行。
- kb-12白话明确系统片段/不是审核文档：允许，正确。
- doc-34白话明确审核文档：允许，正确。
- `documentId=kb-12` 冒审核：SOURCE_IDENTITY_MISMATCH，正确拒绝。
- `documentId=doc-34` 正确审核：允许。
- `documentId=unknown-56` 冒审核：仍允许。故不只是中文ID未解析，显式未知ID也没有审核身份正证。

源码因果：explicitIdentityContradiction仅解析 documentId/sourceType/reviewStatus 等机器键；中文资料编号未进入该分支。identityContradiction只遍历匹配已知ID的 evidence，未知ID没有匹配便返回false。uniqueTitleMislabel在 reviewedNames 也含同名标题时直接跳过知识库标题，因此两处缺口经同名豁免组合产生绕过。

已有可读且有精确核验的格式：`同名资料.md documentId=kb-12，系统知识片段，不是项目已审核文档`；正式doc使用真实doc-34并白话标已审核项目文档。此格式不输出sourceType/reviewStatus，保留权威ID，符合当前Prompt这两编码禁令，但仍需解决未知ID冒审核，不能当全闭环。

最小实现建议（未实施）：沿用当前逐条引用分组/parser，将正式允许的单一白话标签“资料编号：ID”映射为同一documentId身份键，保留逐条作用域/字段顺序/重复键分组；仅当一条引用肯定宣称已审核身份且有显式ID时，要求该ID实际匹配本次已记录PROJECT_DOCUMENT/REVIEWED正证，不能仅未知则忽略。不能把同行另一ID/同名标题借来补正证；否定和未取得维持原允许；不要求所有正文都机器化，不任意regex整篇扫描白名单放行。既有已知KB冒审、同名正式正例、未知ID中文/机器负例、缺失身份不推测、分句多ID与否定应一起定向验证。

没有修改用途默认分支、Skill或Manifest，也没有以此反例推断真实fresh C02已冒审核；真实该稿明确未取得项目审核身份。反例揭示的是当前防护覆盖缺口。
