package org.ruoyi.ipd.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.GateElement;
import org.ruoyi.ipd.mapper.GateElementMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * DOC-05 已确认的33项要素及14否决集合，仅 dev profile 新库初始化。
 * G1/G2/G3/G4/G5 的否决数为5/4/0/3/2；历史SQL及零填充编号不是当前权威。
 * 存量冲突只诊断，禁止覆盖、恢复历史或追加第二套33项。
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class IpdGateElementSeedInitializer implements ApplicationRunner {

    private final GateElementMapper gateElementMapper;

    /** 要素定义：{gateCode, elementCode, elementName, passStandard, isVeto(Y/N), sortOrder} */
    private static final List<String[]> SEED_ELEMENTS = List.of(
        new String[]{"G1", "G1-1", "市场机会真实性", "≥5家目标客户一手验证或≥1家客户书面意向；客户ID、记录及附件可追；一手验证门槛可配置", "N", "1"},
        new String[]{"G1", "G1-2", "市场规模与目标设定", "TAM/SAM/SOM及销售目标推导；C08销售额、渠道数、NPS、场景数四项基准完整", "Y", "2"},
        new String[]{"G1", "G1-3", "竞争格局与差异化", "≥3家竞品对比，≥2项差异点及6个月内难复制的依据", "N", "3"},
        new String[]{"G1", "G1-4", "技术可行性", "可行，或有条件可行并列明条件；相关BioCV实测及成本/算力依据", "Y", "4"},
        new String[]{"G1", "G1-5", "商业性", "毛利率达到有效项目门槛；测算/报价/成本依据和规则版本明确", "Y", "5"},
        new String[]{"G1", "G1-6", "合规与知识产权", "认证规划无不可逾越障碍；FTO无高风险；适用的C12/Z03合规审查完成", "Y", "6"},
        new String[]{"G1", "G1-7", "资源与组队", "双PM有效承接、资源承诺有据", "Y", "7"},
        new String[]{"G2", "G2-1", "PRD完整性", "已上传有效PRD，需求条目化，每条有优先级与验收标准", "Y", "8"},
        new String[]{"G2", "G2-2", "需求优先级与版本规划", "Must/Should/Could划分与窗口匹配，首版Must覆盖核心场景闭环", "N", "9"},
        new String[]{"G2", "G2-3", "差异化卖点可交付性", "每个卖点有可实现结论和成本增量确认", "Y", "10"},
        new String[]{"G2", "G2-4", "价值定价与毛利复核", "最终方案成本/定价达到有效项目门槛；与G1配置来源和版本可追", "Y", "11"},
        new String[]{"G2", "G2-5", "技术方案与里程碑", "总体方案已定；P08关键EVT/DVT/PVT/GTM日期完整，模板适用性有依据", "Y", "12"},
        new String[]{"G2", "G2-6", "认证与法规清单确认", "目标市场认证清单及周期进行中也可通过G2；保留当前缺口、责任人、计划和后续结果检查关联", "N", "13"},
        new String[]{"G3", "G3-1", "进度与里程碑", "双周偏差≤5工作日或有追赶计划；采用法定调休日历", "N", "14"},
        new String[]{"G3", "G3-2", "场景完整度", "PRD核心用户场景端到端走查证据", "N", "15"},
        new String[]{"G3", "G3-3", "需求变更情况", "累计变更率、根因与趋势；原基准≤15%、预警12%均参数化", "N", "16"},
        new String[]{"G3", "G3-4", "技术风险与阻塞", "P0风险、负责人、升级记录可追；两次连续出现P0且均未升级时升级双方组长", "N", "17"},
        new String[]{"G3", "G3-5", "成本与合规跟踪", "成本偏差≤5%及认证送检进度记录，关联风险计划", "N", "18"},
        new String[]{"G4", "G4-1", "产品就绪", "DVT、V02认证、V03试用、V06量产准入结果齐备，P0闭环", "Y", "19"},
        new String[]{"G4", "G4-2", "质量与缺陷", "P0清零；P1≤3且有workaround，缺陷状态/版本可回读", "Y", "20"},
        new String[]{"G4", "G4-3", "供应与备货", "L05首批量产完成、备货覆盖首批预测，有确认记录", "N", "21"},
        new String[]{"G4", "G4-4", "价格与渠道体系", "L02价格政策发布、目标渠道覆盖≥60%，签约及知悉证据", "N", "22"},
        new String[]{"G4", "G4-5", "销售工具与培训", "L03工具包齐备，核心渠道培训覆盖≥80%，含适用海外分支", "N", "23"},
        new String[]{"G4", "G4-6", "本地化与合规落地", "V07物料与V12/Z05本地化验收逐项落实；目标市场强制认证实际已取得", "Y", "24"},
        new String[]{"G4", "G4-7", "售后与支持", "V08售后方案、备件、服务话术、退换货政策已就绪", "N", "25"},
        new String[]{"G4", "G4-8", "GTM方案可执行性", "区域/渠道/节奏具体，首批≥10家目标客户且各有责任人", "N", "26"},
        new String[]{"G5", "G5-1", "销售达成情况", "L08起90日实际回款÷立项目标；默认25%预警线可配置", "N", "27"},
        new String[]{"G5", "G5-2", "渠道与场景覆盖", "渠道和场景覆盖各≥目标50%，原始签约/验收可追", "N", "28"},
        new String[]{"G5", "G5-3", "客户反馈与质量", "P0问题全部闭环；NPS达目标或有改进计划，引用反馈/质量记录", "Y", "29"},
        new String[]{"G5", "G5-4", "需求准确率复盘", "变更率统计完成、根因归类，对照15%目标", "N", "30"},
        new String[]{"G5", "G5-5", "上市准时性与窗口命中", "实际与承诺/计划窗口差异已计量并归因；原指标15/30天分别对应", "N", "31"},
        new String[]{"G5", "G5-6", "利润与成本复盘", "实际与立项毛利偏差≤5个百分点或已归因，引用有效测算版本", "N", "32"},
        new String[]{"G5", "G5-7", "迭代与生命周期决策", "明确加速/迭代/扩区/限售/停产决议、责任人及后续动作", "Y", "33"}
    );

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        var existing = gateElementMapper.selectSeedPreflightIncludingDeleted();
        if (existing == null) throw new IllegalStateException("Gate seed preflight unavailable");
        var definitions = SEED_ELEMENTS.stream().collect(java.util.stream.Collectors.toMap(d -> d[1], d -> d));
        var seen = new java.util.HashSet<String>();
        for (GateElement row : existing) {
            if (row == null || row.getElementCode() == null) throw new IllegalStateException("Gate seed preflight row unavailable");
            var def = definitions.get(row.getElementCode());
            if (def == null) {
                String legacy = row.getElementCode().replaceFirst("^(G[1-5])-0([1-9])$", "$1-$2");
                // 超管合法自定义、draft和archived均不属于本次33项种子编号，不参与覆盖或阻断。
                if (!definitions.containsKey(legacy)) continue;
                // 经独立迁移归档停用的旧种子保留历史，但不再与当前规范发布集合竞争。
                if (!"published".equals(row.getStatus()) || !"1".equals(row.getEnabled())
                    || !"0".equals(row.getDelFlag())) continue;
            }
            if (def == null || !"0".equals(row.getDelFlag()) || !seen.add(row.getElementCode())
                || !def[0].equals(row.getGateCode()) || !def[2].equals(row.getElementName())
                || !def[3].equals(row.getPassStandard())
                || !("Y".equals(def[4]) ? "1" : "0").equals(row.getIsVeto())) {
                log.error("[GateElementSeed] status=CONFLICT operation=READ_ONLY seedWrite=BLOCKED elementCode={}；存量定义与DOC-05不一致，须保留历史并单独迁移", row == null ? "null" : row.getElementCode());
                return;
            }
        }
        int inserted = 0;
        int skipped = 0;
        for (String[] def : SEED_ELEMENTS) {
            String gateCode = def[0];
            String elementCode = def[1];
            if (seen.contains(elementCode)) {
                skipped++;
                continue;
            }
            GateElement e = GateElement.builder()
                .gateCode(gateCode)
                .elementCode(elementCode)
                .elementName(def[2])
                .passStandard(def[3])
                .isVeto("Y".equals(def[4]) ? "1" : "0")
                .sortOrder(Integer.parseInt(def[5]))
                .delFlag("0")
                .enabled("1")
                .status("published")
                .version(1)
                .vetoDualRequired("0")
                .thresholdJson(null)
                .build();
            gateElementMapper.insert(e);
            inserted++;
        }
        log.info("[GateElementSeed] 33 项要素种子初始化完成：新增 {} 项，跳过已存在 {} 项", inserted, skipped);
    }
}
