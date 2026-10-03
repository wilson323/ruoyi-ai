# G 独立产物质量复核

裁决：仅作为「资料核对与缺项报告」可进入现 ai_documents GENERATED 待审核链；不认可 C02 业务完成、完整竞品分析或任何 Gate 通过。本复核未 apply/review、未出站、未改源码/target。

对象 run2106082915170418689 / artifact77f8283b1a6849798dc6b39ce4da4f9b。实际 ipd_dev 只读查询产物正文及本运行 SOURCE/TOOL_RESULT；库 content_sha256 与 SHA2(content,256) 均为 d26e346264dbed18a710fd216803109d7b4151e6b9d27d767deb6a9a1c77e7b8，现 DRAFT/document_id=NULL。未读取或保存内部思考。

实质依据：SOURCE18–21、46–48共7次本地检索，全部 sourceEvidence 是 KNOWLEDGE_FRAGMENT / NOT_PROJECT_DOCUMENT；正文明确没有取得本项目已审核文档，把模板实例与本产品事实区分，引用表内文件名在这些 sourceEvidence 中可回核。SOURCE22–25、44–45共6次MCP均 FAILED/INITIALIZE/PROTOCOL_OR_TRANSPORT/citationChars=0，TOOL_RESULT30–33、49–50对应ERROR；正文如实声明未取得远端有效回答。TOOL_RESULT26–29、51–53对应7次本地SUCCESS，故13次准确，SUCCESS不表示向量全部健康（SOURCE18仍PARTIAL）。正文只登记其他主体的卡片与低可信转述，未把其中数字/规格赋给R218-FIX10，没有生成无依据对比或Gate结论。

现 competitor-analysis-ipd/SKILL.md:21明确「无竞品名单则停止比较，只出缺项」，本正文停止比较与此吻合。ProjectAgentRunService:260–285仅把DRAFT经既有create/reviseGeneratedAuthorized入待审核链；该链不等价动作验收。因此报告不完整不构成本类缺项报告拒绝入待审核的理由。各数字的外部真实性未复核，不能从本结论取得事实背书。

审核时须保留两点精度限制：①正文1.1「全部出处均指向WorkBuddy路径」概括过宽，实际向量模板出处为文件名；「全部远端应用整理结果」也只是保守低可信声明，事件只证明本地知识片段来源。②M7目的裁剪被列为必须补齐并阻断步骤2，与skill:22的「用户声明时」/未声明四维齐全不一致，M9历史型号确认、M10Gate素材也不能升级为新业务门禁。当前M1/M2/M3等真实缺项独立足够停止比较，上述不影响把本稿作为待审核缺项登记，但不得据此要求用户额外审批或声称方法论新增必填项。

最终边界：允许A按既有权限/CAS自行裁决是否手动定档；本报告不替A签署执行许可，不改其verdict JSON。仍需补齐本产品资料并重做实际C02后，才能另核完整业务交付。

## 独立定档后回读

生成→原链定档部分闭环已核。独立Person登录后只GET versions与run（均HTTP200/code0），精简回读 codex-g-independent-apply-readback-20261002.json。新文档2106085620907540481是原链v3、parent2106061816428781569，项目2103659612308828162、GENERATED、MiniMax-M3；v1/v2仍REJECTED。库只读逐字段与HTTP一致，库正文SHA2及content_sha256均 d26e346264dbed18a710fd216803109d7b4151e6b9d27d767deb6a9a1c77e7b8，与独立质量复核的产物正文一致。

库产物77f8283b1a6849798dc6b39ce4da4f9b已APPLIED/document_id2106085620907540481，HTTP run.artifactArchives同关联。本次MODEL_CALL实际含用量seq17=1718/531、43=16709/896、272=24406/2904，求和42833/4331，严格等于新文档token_prompt/token_completion；无用量的调用起始seq5/34/54不伪造为额外消费。旧v3尝试run2106064774604263425/artifact69b9c84f9fe34eb088c9d0c7920a093e仍DRAFT/document_id=NULL。

精度：ai_documents没有index_status列；初次该字段只读查询报1054，随后按实际列重查成功，不能虚称DB索引字段已核。versions响应也不含该字段；NOT_INDEXED来自A原apply响应及AiDocumentService:254既有调用方返回合同。没有READY证据，更无审核/索引就绪推断。未重apply、review、出站、写DB/source/target。人工审核、完整竞品分析、C02业务验收与Gate批准均未发生。
