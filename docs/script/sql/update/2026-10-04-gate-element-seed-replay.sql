-- =====================================================================
-- 文件：2026-10-04-gate-element-seed-replay.sql
-- 日期：2026-10-04
-- 状态：草案（draft）—— **本文件尚未被任何环境 apply 过**，仓库里此前也没有等价物。
--       文件名未带 -draft- 后缀，状态以本行为准。本仓两种惯例并存（实测：
--       docs/script/sql 下 5 份用 -draft- 命名、20 份以文件头标注），
--       本文件沿用同批兄弟件 2026-10-04-ipd-g2-6-veto-align.sql 的写法（头注状态）。
-- 库：ipd_dev（127.0.0.1:13306）；仅新增脚本，不改写任何既有迁移正文。
-- =====================================================================
--
-- 【本脚本解决什么】
--   现网库 gate_review_elements 种子段（id 1948090500–1948090532，33 行）当前是
--   DOC-05 口径（要素名/通过标准），且否决位为 14。但这次修正发生在 2026-10-03 20:00，
--   是**直接改库**做的，仓库里没有任何脚本能复现它。
--   后果：现网没问题，但**装不出第二个正确的环境**——新环境照仓库脚本按序 apply，
--   会得到「15 项否决位 + 旧措辞」的种子段。
--   本脚本把这段修正补成一条可重放路径：对已正确的库零改动，对错库把它拉正。
--
-- 【目标值来源 · 从现网读出后内联，不手写】
--   下方 §3 的 33 组 old/new 值由 2026-10-04 对 ipd_dev 只读查询 + 逐行对拍得到：
--     · old_* = 仓库 SQL 种子（2026-09-05-ipd-p0-seed-elements.sql）apply 后的值；
--     · new_* = 现网当前值（= Java 种子 org.ruoyi.ipd.config.IpdGateElementSeedInitializer
--               的 DOC-05 口径，逐行一致，已核对）。
--   new_* 与现网逐字节比对通过，故本脚本可离线重放，不依赖「再连一次现网」。
--
-- 【实测差异全貌（2026-10-04，ipd_dev 只读 + 临时表重放）】
--   把仓库脚本按序重放进临时表，与现网逐列比对，种子段 33 行的差异只有四列：
--     · pass_standard   33/33 不同
--     · element_name     2/33 不同（G2-1「PRD 完整性」、G4-8「GTM 方案可执行性」多了空格）
--     · is_veto          1/33 不同（只有 G2-6：仓库链给 '1'，现网是 '0'）
--     · del_flag        33/33 不同（见下方 §4 与「§4 的 del_flag 还原的到底是哪一次操作」）
--   其余列（status/enabled/version/veto_dual_required/threshold_json/sort_order/
--   gate_code/tenant_id/sign_due_at/sign_extension_count/remark）逐行完全一致，故**本脚本
--   一个都不碰**——只动上面四列，避免连带覆盖将来的人工改动。
--
-- 【⚠️ 顺序依赖 · 本文件的存在就是为了压过谁】
--   1) docs/script/sql/update/2026-09-05-ipd-p0-seed-elements.sql
--        写入旧措辞 + 把 G2-6 的 is_veto 置 '1'（全库 15 否决位）。新库的第一步。
--   2) docs/script/sql/update/2026-09-06-ipd-p161-gate-element-lifecycle.sql 第 3 段
--        UPDATE ... SET is_veto='1' WHERE element_code='G2-6' AND is_veto='0';
--        **方向是反的，且看着幂等、实际单向翻转**：对已经正确的库（G2-6='0'）
--        再跑一次会把它翻回错的 '1'。本脚本文件名日期在后，按序 apply 会在它之后执行，
--        §3 的 is_veto 段能把它压回去。
--        （该文件不在本卡改动范围：是否修改由 owner 决定。）
--   3) docs/script/sql/update/2026-09-26-r219u1-owner-auth-bonus-version-gate-seed.sql 第 4 段
--        把规范编号行整体软删（del_flag='1'）并写 remark 留痕。见 §4。
--   4) docs/script/sql/update/2026-10-04-ipd-g2-6-veto-align.sql（兄弟件，只对齐 G2-6 一行）
--        文件名排序里 'g' < 'i'，所以本文件在它**之前**执行。两者目标一致、互不冲突：
--        本脚本跑完后 G2-6 已是 '0' 且文本已是 DOC-05，该件的两个 WHERE 守卫都不再命中，
--        退化为 0 行（幂等）。反过来先跑它再跑本脚本，结果也相同。
--   ⇒ 无论这两个 10-04 文件的先后，最终都收敛到同一状态。
--
-- 【幂等性论证 · 为什么重跑结果不变（不是「有 WHERE 所以幂等」）】
--   §3 是「按 (id, element_code) 定位」+「逐列比对旧值」的双重结构：
--     a. JOIN 条件 g.id = d.id AND g.element_code = d.element_code：锁死 33 行，
--        行数、行身份都不会漂移；
--     b. WHERE 判定「需要改」的条件是 **该列既是旧值、又确实与目标值不同**
--        （d.old_x <> d.new_x AND g.x = d.old_x），三列取或；
--     c. SET 只在「该列当前值 = 旧值」时才把它换成新值（CASE WHEN）。
--   ⚠️ 这里有一个容易写错、且实测踩到过的地方：若 WHERE 只写「至少一列等于旧值」，
--      对本例**不幂等**。33 行里有 31 行的 element_name 与 is_veto 旧值本就等于新值
--      （两份种子在这两列相同），该条件会一直为真，于是每次重跑都把 update_time 刷成 NOW()
--      ——内容没变，但「重复执行结果不变」已经不成立。补上 old_x <> new_x 这一半后：
--      · 新状态下 pass_standard 33 行全部等于新值（≠ 旧值）；
--      · G2-1 / G4-8 的名字已等于新值，G2-6 的 is_veto 已等于新值；
--      · 其余 31 行的名字与否决位虽然仍等于旧值，但那里 old = new，第一个合取项为假；
--      ⇒ 第二次执行 0 行受影响，update_time 也不再变动（已实测，见文末自证）。
--   §4（del_flag）同理：还原后 del_flag 已非 '1'，守卫不再命中。
--
-- 【作用域隔离 · 为什么不碰种子段外的 64 行】
--   全表 97 行 = 种子段 33 行（id 1948090500–1948090532）+ 段外 64 行（测试数据）。
--   §3 的 JOIN 派生表里**只有 33 个 id 字面量**，且再加 g.id BETWEEN 1948090500 AND
--   1948090532 一道范围守卫；JOIN 的 ON 还要求 element_code 同时相等。段外任何行
--   （包括那 33 行零填充编号 G1-01… 与 31 行其它测试数据）都不在任何一行的 id 列表里，
--   连 JOIN 阶段都进不来，因此不可能被 UPDATE 命中。
--   §4 的范围守卫同样是 id BETWEEN ... + element_code REGEXP '^G[1-5]-[1-9]$'
--   （该正则**不匹配**零填充的 G1-01，因为它们多一位数字）。
--
-- 【§4 的 del_flag 还原的到底是哪一次操作】
--   现网种子段 33 行的 del_flag 是 '0'（业务可见），但 remark 里留着
--   「 R219U1软删:旧代seed与新代Initializer双套并存,owner 2026-09-26授权清理」——
--   说明 2026-09-26 那次软删**确实执行过**，只是后来被还原成 '0'。
--   实测佐证：种子段与零填充两代共 66 行的 update_time **完全相同**
--   （2026-10-03 20:00:55，COUNT(DISTINCT update_time)=1）——同一瞬间整批改写。
--   那一批同时做了三件事：把规范号行的内容改成 DOC-05、把规范号的 del_flag 还原为 '0'、
--   把零填充那代置为 archived + enabled='0'。
--
--   ⚠️ 本段此前写成「等于反转一次曾获 owner 授权的清理」，**那句话是错的，已更正**：
--   §4 并不是在推翻 owner 的决定。9-26 那份脚本自称软删的是「旧代」，但它把两代叫反了——
--   DOC-05 权威初始化器 org.ruoyi.ipd.config.IpdGateElementSeedInitializer 的 SEED_ELEMENTS
--   用的是 G1-1 式，而它把 ^G[1-5]-0[1-9]$（G1-01）定义为 LEGACY_SEED_CODE，
--   类注释原文「历史SQL及零填充编号不是当前权威」。
--   也就是说，那次软删删掉的恰恰是**正确的那一代**；§4 的方向与 owner 的清理意图一致。
--   若新环境按序 apply 而不跑 §4，这 33 行会停在 del_flag='1'：定义对业务不可见，
--   Gate 评审要素整段为空——比「15 否决位」更严重。
--   故 §4 单列一段并加 remark 守卫（只还原带该机器留痕的行，绝不复活人工软删）。
--   若 owner 认为该清理才是应有状态，**只需删除 §4，其余各段不受影响**。
--
-- 【已知限制 · 本脚本不触碰 status / enabled】
--   本脚本只对齐内容列（element_name / pass_standard / is_veto）与 del_flag，
--   **不碰 status / enabled**。若某环境里这 33 行处于 draft / 停用态，本脚本不会把它们
--   拉回可见——此时需要人工判断是否该发布，**脚本不代做这个决定**。
--   实测依据：把 33 行置成 status='draft' + enabled='0' + del_flag='1' 后执行本脚本，
--   §4 确实把 del_flag 还原为 '0'（ROW_COUNT()=33），但 status/enabled 原封不动，
--   应用可见数（published+enabled=1+del_flag=0）仍为 0。
--   ⇒ **脚本不具备修复该状态的能力（已证实）；仓库链目前也不会产生该状态（未证实会发生）**：
--     9-05 种子显式写 enabled='1'，status 由 P161 的 ALTER 以 DEFAULT 'published' 加入，
--     且 docs/script/sql 下没有任何脚本把规范号行置成 draft/archived 或 enabled='0'
--     （唯一的 archived 写入方是测试数据件，用的是 G2-ARCH-0x 编号）。
--   刻意不补这段对齐：§4 已经有一次状态翻转，再补一次会把「有人故意停用的要素」复活，
--   与 9-26 那次「看似无害的状态翻转却删错对象」是同一个坑，不重复挖。
--
-- 【回滚】
--   UPDATE 不可逆。执行前若需可回滚，先做整表快照：
--     CREATE TABLE gate_review_elements_bak_YYYYMMDD AS SELECT * FROM gate_review_elements;
--   回滚方向见文末「回滚」段（把 new_* 换回 old_*，同样按 (id, element_code) 定位）。
-- =====================================================================

SELECT DATABASE() AS selected_catalog;

-- ---------------------------------------------------------------------
-- 执行前预检（只读，无副作用）
--   期望：若本库已 apply 过 2026-09-05 SQL 种子，会看到 15 否决位 + 旧措辞特征；
--         若本库已是 DOC-05 口径（现网现状），会看到 14 否决位、0 行旧措辞。
-- ---------------------------------------------------------------------
SELECT 'before 种子段否决位分布（现网应为 0=19 / 1=14）' AS phase, is_veto, COUNT(*) AS cnt
FROM gate_review_elements
WHERE id BETWEEN 1948090500 AND 1948090532
GROUP BY is_veto ORDER BY is_veto;

SELECT 'before 仍带 SQL 种子旧措辞特征的行数（现网应为 0）' AS phase,
       SUM(pass_standard LIKE '✅%' OR pass_standard LIKE '%=否决%') AS legacy_text_rows
FROM gate_review_elements
WHERE id BETWEEN 1948090500 AND 1948090532;

SELECT 'before 种子段 del_flag 分布（现网应为 0=33）' AS phase, del_flag, COUNT(*) AS cnt
FROM gate_review_elements
WHERE id BETWEEN 1948090500 AND 1948090532
GROUP BY del_flag ORDER BY del_flag;

-- ---------------------------------------------------------------------
-- §3 对齐三列：element_name / pass_standard / is_veto
--    单条语句、33 组值内联。只改「仍等于仓库旧值」的列，人工改过的列原样保留。
--    新值来源：现网读出（= DOC-05 / Java 种子口径）。
-- ---------------------------------------------------------------------
UPDATE gate_review_elements g
JOIN (
  SELECT 1948090500 AS id, 'G1-1' AS element_code,
         '市场机会真实性' AS old_name, '市场机会真实性' AS new_name,
         '✅ ≥5 家目标客户一手验证记录；或 ≥1 家客户书面意向（二选一）。参数 gate.g1.minCustomerVerifications=5' AS old_pass, '≥5家目标客户一手验证或≥1家客户书面意向；客户ID、记录及附件可追；一手验证门槛可配置' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090501 AS id, 'G1-2' AS element_code,
         '市场规模与目标设定' AS old_name, '市场规模与目标设定' AS new_name,
         '目标销售额有自下而上推导（客户数×客单×渗透率）或可比产品对标；四项基准值（目标销售额/渠道数/NPS/场景数）已录入' AS old_pass, 'TAM/SAM/SOM及销售目标推导；C08销售额、渠道数、NPS、场景数四项基准完整' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090502 AS id, 'G1-3' AS element_code,
         '竞争格局与差异化' AS old_name, '竞争格局与差异化' AS new_name,
         '差异点≥2 项且竞品 6 个月内难以复制（竞品≥3 家对比）' AS old_pass, '≥3家竞品对比，≥2项差异点及6个月内难复制的依据' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090503 AS id, 'G1-4' AS element_code,
         '技术可行性' AS old_name, '技术可行性' AS new_name,
         '预研结论为可行或有条件可行+明确条件；结论不可行=否决' AS old_pass, '可行，或有条件可行并列明条件；相关BioCV实测及成本/算力依据' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090504 AS id, 'G1-5' AS element_code,
         '商业性' AS old_name, '商业性' AS new_name,
         '毛利率 ≥ 产品线门槛（可配置 gate.grossMarginThreshold）；毛利率为负=否决' AS old_pass, '毛利率达到有效项目门槛；测算/报价/成本依据和规则版本明确' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090505 AS id, 'G1-6' AS element_code,
         '合规与知识产权' AS old_name, '合规与知识产权' AS new_name,
         '认证清单已确认且无不可逾越障碍；FTO 无高风险专利；Z03 合规审查未通过/认证不可逾越=否决' AS old_pass, '认证规划无不可逾越障碍；FTO无高风险；适用的C12/Z03合规审查完成' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090506 AS id, 'G1-7' AS element_code,
         '资源与组队' AS old_name, '资源与组队' AS new_name,
         '双PM 均已确认承接；研发PM 未到位=否决' AS old_pass, '双PM有效承接、资源承诺有据' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090507 AS id, 'G2-1' AS element_code,
         'PRD 完整性' AS old_name, 'PRD完整性' AS new_name,
         '需求条目化率 100%，每条含验收标准；PRD 未上传=否决' AS old_pass, '已上传有效PRD，需求条目化，每条有优先级与验收标准' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090508 AS id, 'G2-2' AS element_code,
         '需求优先级与版本规划' AS old_name, '需求优先级与版本规划' AS new_name,
         '首版 Must 需求可支撑核心场景闭环' AS old_pass, 'Must/Should/Could划分与窗口匹配，首版Must覆盖核心场景闭环' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090509 AS id, 'G2-3' AS element_code,
         '差异化卖点可交付性' AS old_name, '差异化卖点可交付性' AS new_name,
         '每个卖点有研发侧可实现+成本增量确认；卖点被判定不可实现=否决' AS old_pass, '每个卖点有可实现结论和成本增量确认' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090510 AS id, 'G2-4' AS element_code,
         '价值定价与毛利复核' AS old_name, '价值定价与毛利复核' AS new_name,
         '毛利率 ≥ 门槛；低于门槛且无定价调整方案=否决' AS old_pass, '最终方案成本/定价达到有效项目门槛；与G1配置来源和版本可追' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090511 AS id, 'G2-5' AS element_code,
         '技术方案与里程碑' AS old_name, '技术方案与里程碑' AS new_name,
         '里程碑日期完整（EVT/DVT/PVT/GTM）；里程碑日期缺失=否决' AS old_pass, '总体方案已定；P08关键EVT/DVT/PVT/GTM日期完整，模板适用性有依据' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090512 AS id, 'G2-6' AS element_code,
         '认证与法规清单确认' AS old_name, '认证与法规清单确认' AS new_name,
         '目标市场强制认证全部列入且周期匹配；缺失或周期冲突=否决' AS old_pass, '目标市场认证清单及周期进行中也可通过G2；保留当前缺口、责任人、计划和后续结果检查关联' AS new_pass,
         '1' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090513 AS id, 'G3-1' AS element_code,
         '进度与里程碑' AS old_name, '进度与里程碑' AS new_name,
         '偏差 ≤5 个工作日，或已制定追赶计划' AS old_pass, '双周偏差≤5工作日或有追赶计划；采用法定调休日历' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090514 AS id, 'G3-2' AS element_code,
         '场景完整度' AS old_name, '场景完整度' AS new_name,
         '每个核心场景端到端可走通' AS old_pass, 'PRD核心用户场景端到端走查证据' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090515 AS id, 'G3-3' AS element_code,
         '需求变更情况' AS old_name, '需求变更情况' AS new_name,
         '累计变更率 ≤15%（预警线 12%）' AS old_pass, '累计变更率、根因与趋势；原基准≤15%、预警12%均参数化' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090516 AS id, 'G3-4' AS element_code,
         '技术风险与阻塞' AS old_name, '技术风险与阻塞' AS new_name,
         '无 P0 级阻塞；有则已升级并明确责任人' AS old_pass, 'P0风险、负责人、升级记录可追；两次连续出现P0且均未升级时升级双方组长' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090517 AS id, 'G3-5' AS element_code,
         '成本与合规跟踪' AS old_name, '成本与合规跟踪' AS new_name,
         '成本偏差 ≤5%；认证进度正常' AS old_pass, '成本偏差≤5%及认证送检进度记录，关联风险计划' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090518 AS id, 'G4-1' AS element_code,
         '产品就绪' AS old_name, '产品就绪' AS new_name,
         'DVT/V02 认证/V03 Beta/V06 量产准入全部通过；V02 未通过或量产准入未通过=否决' AS old_pass, 'DVT、V02认证、V03试用、V06量产准入结果齐备，P0闭环' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090519 AS id, 'G4-2' AS element_code,
         '质量与缺陷' AS old_name, '质量与缺陷' AS new_name,
         'P0 清零，P1 ≤3 且有 workaround；P0 未清零=否决' AS old_pass, 'P0清零；P1≤3且有workaround，缺陷状态/版本可回读' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090520 AS id, 'G4-3' AS element_code,
         '供应与备货' AS old_name, '供应与备货' AS new_name,
         '首批量产完成，备货满足首批订单预测' AS old_pass, 'L05首批量产完成、备货覆盖首批预测，有确认记录' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090521 AS id, 'G4-4' AS element_code,
         '价格与渠道体系' AS old_name, '价格与渠道体系' AS new_name,
         '价格体系已发布，目标渠道覆盖率 ≥60%' AS old_pass, 'L02价格政策发布、目标渠道覆盖≥60%，签约及知悉证据' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090522 AS id, 'G4-5' AS element_code,
         '销售工具与培训' AS old_name, '销售工具与培训' AS new_name,
         '工具包齐备，核心渠道培训覆盖率 ≥80%（含海外分支）' AS old_pass, 'L03工具包齐备，核心渠道培训覆盖≥80%，含适用海外分支' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090523 AS id, 'G4-6' AS element_code,
         '本地化与合规落地' AS old_name, '本地化与合规落地' AS new_name,
         '本地化验收清单逐项打勾完成；目标市场强制认证未取得/Z05 未完成=否决' AS old_pass, 'V07物料与V12/Z05本地化验收逐项落实；目标市场强制认证实际已取得' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090524 AS id, 'G4-7' AS element_code,
         '售后与支持' AS old_name, '售后与支持' AS new_name,
         '售后维修方案/备件/话术/退换货政策已就绪' AS old_pass, 'V08售后方案、备件、服务话术、退换货政策已就绪' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090525 AS id, 'G4-8' AS element_code,
         'GTM 方案可执行性' AS old_name, 'GTM方案可执行性' AS new_name,
         '首批目标客户名单 ≥10 家且已分配责任人' AS old_pass, '区域/渠道/节奏具体，首批≥10家目标客户且各有责任人' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090526 AS id, 'G5-1' AS element_code,
         '销售达成情况' AS old_name, '销售达成情况' AS new_name,
         '90 天累计达成率 ≥25%（预警线，非否决）；回款口径 Q2' AS old_pass, 'L08起90日实际回款÷立项目标；默认25%预警线可配置' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090527 AS id, 'G5-2' AS element_code,
         '渠道与场景覆盖' AS old_name, '渠道与场景覆盖' AS new_name,
         '渠道覆盖 ≥50% 目标，场景覆盖 ≥50% 目标' AS old_pass, '渠道和场景覆盖各≥目标50%，原始签约/验收可追' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090528 AS id, 'G5-3' AS element_code,
         '客户反馈与质量' AS old_name, '客户反馈与质量' AS new_name,
         'P0 问题 100% 闭环；NPS 达目标或已制定改进计划；P0 未闭环=否决' AS old_pass, 'P0问题全部闭环；NPS达目标或有改进计划，引用反馈/质量记录' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
  UNION ALL
  SELECT 1948090529 AS id, 'G5-4' AS element_code,
         '需求准确率复盘' AS old_name, '需求准确率复盘' AS new_name,
         '变更率统计完成，根因已归类' AS old_pass, '变更率统计完成、根因归类，对照15%目标' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090530 AS id, 'G5-5' AS element_code,
         '上市准时性与窗口命中' AS old_name, '上市准时性与窗口命中' AS new_name,
         '两项偏差已计量并归因（≤15 天/≤30 天）' AS old_pass, '实际与承诺/计划窗口差异已计量并归因；原指标15/30天分别对应' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090531 AS id, 'G5-6' AS element_code,
         '利润与成本复盘' AS old_name, '利润与成本复盘' AS new_name,
         '毛利率偏差 ≤5 个百分点或已归因' AS old_pass, '实际与立项毛利偏差≤5个百分点或已归因，引用有效测算版本' AS new_pass,
         '0' AS old_veto, '0' AS new_veto
  UNION ALL
  SELECT 1948090532 AS id, 'G5-7' AS element_code,
         '迭代与生命周期决策' AS old_name, '迭代与生命周期决策' AS new_name,
         '已形成明确决议（加速/迭代/扩展/限售/停产）并指定责任人；未形成决议=否决' AS old_pass, '明确加速/迭代/扩区/限售/停产决议、责任人及后续动作' AS new_pass,
         '1' AS old_veto, '1' AS new_veto
) d ON g.id = d.id AND g.element_code = d.element_code
SET g.element_name  = CASE WHEN g.element_name  = d.old_name THEN d.new_name ELSE g.element_name  END,
    g.pass_standard = CASE WHEN g.pass_standard = d.old_pass THEN d.new_pass ELSE g.pass_standard END,
    g.is_veto       = CASE WHEN g.is_veto       = d.old_veto THEN d.new_veto ELSE g.is_veto       END,
    g.update_time   = NOW()
WHERE g.id BETWEEN 1948090500 AND 1948090532
  AND (   (d.old_name <> d.new_name AND g.element_name  = d.old_name)
       OR (d.old_pass <> d.new_pass AND g.pass_standard = d.old_pass)
       OR (d.old_veto <> d.new_veto AND g.is_veto       = d.old_veto));

-- ---------------------------------------------------------------------
-- §4 还原 2026-09-26 那次机器软删（详见文首「需 owner 确认的一项」）
--   守卫三重：id 在种子段 + 规范编号 + del_flag='1' + 带机器留痕 remark。
--   remark 原样保留（现网也保留着它），只翻 del_flag。
--   不认同这一步就删掉本段，§3 不受影响。
-- ---------------------------------------------------------------------
UPDATE gate_review_elements
SET del_flag = '0',
    update_time = NOW()
WHERE id BETWEEN 1948090500 AND 1948090532
  AND element_code REGEXP '^G[1-5]-[1-9]$'
  AND del_flag = '1'
  AND remark LIKE '%R219U1软删%';

-- ---------------------------------------------------------------------
-- 执行后回读核验（期望与现网一致：0=19 / 1=14，旧措辞 0 行，del_flag 全 0）
-- ---------------------------------------------------------------------
SELECT 'after 种子段否决位分布（应为 0=19 / 1=14）' AS phase, is_veto, COUNT(*) AS cnt
FROM gate_review_elements
WHERE id BETWEEN 1948090500 AND 1948090532
GROUP BY is_veto ORDER BY is_veto;

SELECT 'after 仍带旧措辞特征的行数（应为 0）' AS phase,
       SUM(pass_standard LIKE '✅%' OR pass_standard LIKE '%=否决%') AS legacy_text_rows
FROM gate_review_elements
WHERE id BETWEEN 1948090500 AND 1948090532;

SELECT 'after 种子段 del_flag 分布（应为 0=33）' AS phase, del_flag, COUNT(*) AS cnt
FROM gate_review_elements
WHERE id BETWEEN 1948090500 AND 1948090532
GROUP BY del_flag ORDER BY del_flag;

SELECT 'after G2-6 单行（应 is_veto=0 且为 DOC-05 文本）' AS phase,
       id, element_code, element_name, is_veto, pass_standard
FROM gate_review_elements
WHERE id = 1948090512;

-- =====================================================================
-- 自证记录（2026-10-04 实测，不是推演）
--   环境：MySQL 8.0.46 @127.0.0.1:13306。业务库 ipd_dev 全程只读；
--   实验在 ipd_poc（ipd_app 有 ALL PRIVILEGES 的旁支库）用 CREATE TEMPORARY TABLE 做，
--   会话结束即消失，未对任何库写入持久对象。
--   做法：把 2026-09-05 种子 + 2026-09-06 P161 第3段 + 2026-09-26 R219U1 第4段
--         按序重放进临时表得到「错库」，再执行本脚本（表名替换为临时表名），
--         然后与 ipd_dev 现网逐列比对。
--   结果：
--     ① 错库（只跑旧脚本）：33 行 / 否决位 15 / del_flag='0' 的 0 行 / 旧措辞 15 行。
--     ② 跑完本脚本：        33 行 / 否决位 14 / del_flag='0' 的 33 行 / 旧措辞 0 行。
--     ③ 与现网逐列 diff：element_name / pass_standard / is_veto / del_flag / status /
--        enabled / version / veto_dual_required / threshold_json / sort_order /
--        gate_code / tenant_id / remark —— **13 列全部 0 行不同**。
--        （create_time / update_time 不同是预期的：临时表用重放时的 NOW()，且本脚本
--          按惯例刷新 update_time；它们不是正确性列。）
--     ④ 幂等：重跑第二次，33 行零改动。为排除「同一秒 NOW() 相等」造成的假绿，
--        在两次执行之间把 update_time 置为哨兵值 '2000-01-01 00:00:00'，
--        重跑后 33 行哨兵全部原样保留 ⇒ 确实一行未碰。
--     ⑤ 对「本来就是正确状态」的库（现网 33 行原样复制）执行：哨兵同样 33 行原样保留，
--        内容与现网 diff 全 0 ⇒ 0 行受影响。
--   未覆盖：没有真·空库可建（ipd_app 无建库权限），故「全新环境从零 apply 全套迁移」
--           这一步未实测；上面 ① 是用逐段重放等价模拟的。
-- =====================================================================
-- 回滚（仅在确需撤销本脚本时执行）
--   把 §3 的 new_* 与 old_* 互换、并把 §4 反向即可；定位方式与本脚本一致。
--   注意：回滚会让新环境重新回到「15 否决位 + 旧措辞」，仅在 owner 明确要求时执行。
-- =====================================================================
-- 备份建议：
--   CREATE TABLE gate_review_elements_bak_YYYYMMDD AS SELECT * FROM gate_review_elements;
-- 回滚示例（G2-6 一行，其余行同理）：
--   UPDATE gate_review_elements
--   SET is_veto = '1',
--       pass_standard = '目标市场强制认证全部列入且周期匹配；缺失或周期冲突=否决',
--       update_time = NOW()
--   WHERE id = 1948090512 AND element_code = 'G2-6';
-- 撤销 §4：
--   UPDATE gate_review_elements SET del_flag = '1'
--   WHERE id BETWEEN 1948090500 AND 1948090532
--     AND element_code REGEXP '^G[1-5]-[1-9]$' AND remark LIKE '%R219U1软删%';
-- =====================================================================
