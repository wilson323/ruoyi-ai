DRAFT_ONLY，待A明确源码窗口，未应用。
六行：仓库ruoyi-ai；入口market-research/v1能力包与Planner；边界manifest+PlannerActionSkillTest；字段actionCodes/skills/name/version/hash；目标接通已实现C01技能而不扩大其他动作/工具；证据现规格+DB绑定+45个classpath技能哈希，当前运行未验。
候选patch只改包动作/技能清单和描述，新增C01冻结正例与C03/其他技能负例；不改DB、不晋升草案、不改Skill正文或模型。现C02真实middleware消费正例保留，C01消费者正例建议将现模型捕获测试参数化C01/C02以验证实际prompt而非只验plan；最终源码窗口获批后实施该消费者验证。SHA篡改拒绝依赖现SkillCatalog/FrozenSkill测试，仍需A串行执行。Planner本身纯校验无写store，不用mock文档存在声称市场数据/Gate通过。

## 已授权应用

PENDING_VALIDATION。A结束主target窗口后协调者授权应用候选，仅修改上述manifest与ProjectAgentRunPlannerActionSkillTest。现模型捕获测试参数化C01/C02，读取真实classpath技能并在Kernel.execute实际调用的Model.stream SYSTEM消息里断言冻结技能正文只出现一次、各动作关键词存在。另有C01精确name/version/SHA与只读toolIds冻结正例；C03/无关显式skill负例保留。未跑Maven/主target，未写DB。git apply --check及diff --check退出0，JSON结构及45技能SHA独立检查通过。

冻结SHA：
- `ruoyi-modules/ruoyi-ipd/src/main/resources/ipd-skills/capability-packs.json` `c0c96e54af047ba34fb08d046c2dbba8ed05b5001c65c7091e6ea47b628dd518`
- `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/service/ProjectAgentRunPlannerActionSkillTest.java` `8bb9e816b9a7639e120042a144f47fb0c3fc3ab10839de82a41eb18197abcf03`

## 全量旧断言同步

A最新IPD全量3733仅旧manifestDeclaresC02Pack单项断言失败；协调者追加授权仅ProjectAgentSkillCatalogTest。六行：ruoyi-ai；清单测试入口；仅该测试；actionCodes/skills；旧C02单项改精确C01/C02与两技能；源码差异/哈希，Maven由A。更名与DisplayName同步，containsExactly保留并补两技能精确顺序，既有注册/阶段/v2拒绝断言保留。不改生产源码/target、不跑Maven。
修改前SHA 1979db41bf1fb67bf1ef9153475569934f14e9aa432be67733dc43897723c256；修改后SHA 6b06c1b5340b592a9303091b935db7da7fffbed366627187b3e4c0f262cc0200。
