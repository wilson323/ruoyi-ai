# G C02 技能与引用合同只读复核

裁决：规则没有要求机器码进入用户正文；当前缺项稿违反既有 Prompt 白话要求，不能归因于技能强制模板。默认用途分支正式明确，G-06误加必需补项是输出合同偏离。未改 skill/manifest/源码/index、未晋升映射、无模型或网络请求。

源码证据：competitor-analysis-ipd/SKILL.md 第22行正式规定“目的裁剪（用户声明时）……未声明则四维齐全、篇幅克制”；步骤3每格附来源、步骤6五段输出不强制 sourceType/reviewStatus/knowledgeId。第13行 ≥3家/≥2差异点是支撑 G1-3由人判定，不能把目的声明升级为开工前置。

ProjectAgentPrompt.build 明确只有结构 SOURCE 的 PROJECT_DOCUMENT+REVIEWED 才能称已审核项目文档；紧接着要求正文用“项目记录”“系统知识片段”“已审核项目文档”等白话，并明确“不要输出 sourceType、reviewStatus 等内部编码”。MCP 没有原始出处时使用已有固定白话“远端应用回答，原始出处未取得”。当前稿第0节把内部规则和编码复述为用户可见表格，虽身份真实仍违反此要求。

ProjectKnowledgeFragmentTextSearch/ProjectKnowledgeVectorSearch 的工具片段标头确实含 sourceType、knowledgeId、documentId、reviewStatus；这些为模型输入的权威取证字段，并无要求原样交付正文。其表面形式提供模型复制诱因，这是风险解释，不能证明模型为何忽略白话提示。技能不强制机器引用模板。

CompletionGate.noteSource 从结构 sourceEvidence 保存权威类别/ID/审核身份；正文先查显式字段矛盾，兼查标题与已审核身份冒认。门禁允许正确机器码或白话，不校验白话风格，也不判用途缺项。这是完成门和样式提示的不同覆盖范围，无需放宽身份判据。

沿用现有五段报告与来源表即可：例如“模板B.md（资料编号：D-03）｜系统知识片段，不是项目已审核文档”；远端写“产线知识库应用回答，原始出处未取得”；项目四行上下文写“项目记录，不是审核证据”。实际 SOURCE 保留原 documentId/fragmentId/hash/type/reviewStatus 不变，用户正文保留资料名和原资料编号，能回溯而不拷贝类别机器码。对同名正式文档与知识片段，单靠标题仍有歧义；现 Gate 精确 ID 语法是 documentId=，中文“资料编号”尚不被显式 ID 分支解析，不能宣称新中文格式新增同名精确身份防护。此限制不需新文档轨，也不应伪称已解决。

独立纯 javac probe 使用当前真实 Gate、结构来源 D-03：上述白话表行及保留 documentId 的白话句均允许；冒称已审核及伪 PROJECT_DOCUMENT/REVIEWED 均返回 SOURCE_IDENTITY_MISMATCH。编译0、probe0，详情 codex-g-citation-contract-probe-20261002.json。仅格式与身份规则验证，不代替真实 C02完成/产物审核。
