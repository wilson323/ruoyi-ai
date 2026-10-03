# C02 默认用途合同门禁

两路径最小源码修改：Gate保留无参构造器，新增ProjectAgentCompletionGate(String actionCode)，只C02检查；固定reason增加SKILL_CONTRACT_MISMATCH，generic错误码不变。RunHandle上下文root另接现run.actionCode，无RunSpec依赖。

实际847d草稿G06缺项行在目的裁剪字段说明用户声明时、末列明确未声明默认四维，却邻近影响列肯定阻塞C02步骤2。判据只限带上述默认/可选分支的用途字段所在同一缺项表行及邻列；G01候选名单行其他栏提目的裁剪不触发。非表正文只匹配用途未声明/未声明用途或明确可选目的主体的短肯定必需阻塞声明。否定、条件行、块引用、可选影响列排除；并非一般用途词或阻塞词扫描。用途已声明但原报价缺失的行保留。

独立temp javac+JUnit launcher GateTest实际21found/started/pass，0失败。保留19既有身份/数字测试，新增真实row拒及默认/否定/条件/引用/其他缺项/非C02允许。没有主Maven/target/index写入。

完整DB正文5441字符另真实probe：无检索输入时C02=SKILL_CONTRACT_MISMATCH、C01=null；附该run原SOURCE时两者均先返回既有UNSUPPORTED_MEASUREMENT。最初probe误期待新reason优先而失败，原日志保留；最终四对照通过。此发现不能放宽或调序数字规则，也不能声称完整source-backed正文首因是新reason。此重建缺原citationText，标记RECONSTRUCTION_BASIS_INCOMPLETE；不能据此推断新run历史SUCCEEDED与当前生产判据不一致。

当前源码/test冻结SHA和正文SHA在JSON，源码已修但运行接线/重载未验，状态PENDING_RUNTIME。Gate没有持有原用户用途声明字段，此判据只纠正同一可选默认分支被肯定升级为必需的内部矛盾，不推断所有业务目的。没有技能hash、事件、DB或SDK设置变化。


独立root接线复核：RunHandle主构造器先从run.getActionCode冻结actionCode，再new Gate(actionCode)，委托构造器沿用，没有新query或RunSpec依赖。onSource先completion.noteSource(source)，然后仅复制可见map移除citationText，存citationChars/hash后append；生产Gate曾收到完整basis，DB事件不能回造同一basis。完整body无SOURCE对照仅证明新predicate，不能冒称source-backed真实首因。

新增root HandleTest C02反例当前为无编号三列表；冻结Gate限定带G-06编号四列缺项表，因此该fixture不等价真实row，已告知root应使用真实编号row而非扩大判据。其他C02默认/非C02正例与ctor接线静态符合范围，root新定向结果尚未独立读取，未声称通过。


## C purpose-only 候选独立复核

C冻结Gate3fe2c138.../Testb69b1948... temp-only候选：认可可选但必须先声明正确拒、不构成阻塞/无须正确允许；但总体REJECT_CLOSURE_SCOPE_GAP。独立javac probe10输入有5实证缺口。无SOURCE，排除数字重建问题。

三误拒：用途未声明，不构成阻塞，但竞品名单缺失阻塞C02；用途未声明，竞品名单缺失阻塞C02；用途未声明，必须先补竞品名单才能继续。原因blocksOptionalPurpose把整行用途主体继承给后面明确其他主语，affirmativePurposeBlock对任何clause阻塞都拒，违反其他事实缺项不借purpose。

两漏判：用途未声明，无须补报价且必须先声明用途才能继续，返回null（全clause无须免了不同宾语的用途必须）；G06影响列如果用户要求窄化才裁剪，但未声明用途仍阻塞C02，返回null（函数开头若/如果等免整个statement，未进入转折分句）。需绑定用途主体/宾语与对应肯定阻塞，不以整clause或整statement否定/条件免其他肯定，不扫描扩大成所有业务缺项。

root指定两句已实际probe，而非推断；日志见JSON。root/C已收到具体反例。不修改main，现不能将C22tests通过扩成此范围完整闭环。


### C purpose-v2 独立回归

Gate cdbbf3e8...新temp冻结。原五缺口已修，15既定抽查与预期一致；原G06/default/C01的C22JUnit日志已读，仍限纯Gate probe。扩两条原归属/条件范围反例仍误拒：`用途未声明，竞品名单缺失阻塞目的裁剪与四维比较。` 其阻塞主语为竞品名单，purpose的clause .*目的裁剪误将宾语当主语；`用途未声明，如果用户要求窄化，必须先声明用途。` 后段必须仍在前条件范围，当前跳过如果clause但未保持条件范围。两者实际SKILL_CONTRACT_MISMATCH，不是推断。共17输入/2新增误拒日志见JSON，root/C已收到；继续REJECT_CLOSURE_SCOPE_GAP，不main写。

C随后仅格式重冻e381caee.../Test7ea86a00...，C22再过；上述probe对应原语义同cdbbf3e8...，两问题未修，保留原hash与新hash不混称实测新语义。


### C purpose-v3 独立限定验收

ACCEPT_SCOPED_CANDIDATE。冻结Gatef0d8ec7c.../Testd1e128b0...；独立javac读取准确freeze后复跑既定17输入（不增词库），全部预期匹配，原五及后两缺口均修。affirmativePurposeBlock明确用途主语或声明谓词，不能把目的裁剪宾语借作主语；条件flag跨逗号保持，在已测转折/且新segment重置。真实5441完整正文无SOURCE输入C02=SKILL_CONTRACT_MISMATCH/C01=null；重建SOURCE仍两者UNSUPPORTED_MEASUREMENT，此为RECONSTRUCTION_BASIS_INCOMPLETE，不推生产差异。原数字/身份规则未改。

仅认可现缺项表编号、默认/可选语义及已测有限中文主语、声明、条件/否定/转折grammar；其他自然语言句式unsupported，不保证全NLP。没有新增同义词或扩大业务范围。完整工作树定向编译、identity+purpose组合及加载runtime由root另验；本分区只temp独立probe，无主source/index/target/DB写。原v1/v2失败证据保留历史，当前结论以本节为准。


### root组合Gate独立验收

实际组合Gate da3cae4f...及Test04b640ae...冻结复制temp单类javac0，身份13＋用途17观察结果分别与已认可peer日志逐字相同，30/30符合预期（非JUnit count）。purpose方法逐字等C v3，identity方法逐字等G新版，两patch不重叠；HEADf456 noteSource、measurementsMatchQuotes、claimsGate方法逐字未改，来源basis/数字/阶段Gate旧判据保持。组合hash四路径、全部30结果与日志在codex-b-combined-gate-review-20261002.json。认可SCOPED候选，不代表主Maven或runtime加载验收；未写main/source/index/target。
