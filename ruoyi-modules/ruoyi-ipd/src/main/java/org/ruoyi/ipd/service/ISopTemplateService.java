package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.SopTemplate;
import org.ruoyi.ipd.domain.SopTemplateInstance;
import org.ruoyi.ipd.dto.SopTemplateListItem;
import org.ruoyi.ipd.dto.SopTemplateSaveReq;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.SopTemplateInstanceMapper;
import org.ruoyi.ipd.mapper.SopTemplateMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ISopTemplateService 接口（paiban-05 接口化，实现见 {@link SopTemplateService}）。
 */
public interface ISopTemplateService {

    /** 版本列表轻量视图：不拉 mediumtext 正文，CHAR_LENGTH(content) 计算字数；version 倒序。 */
    List<SopTemplateListItem> listByActionCode(String actionCode);

    /** * 当前生效 SOP（PUBLISHED + effectiveTo IS NULL，同 actionCode 下最新一条；含正文）。 */
    /** * 不存在时抛 IpdBusinessException(NOT_FOUND)。 */
    SopTemplate currentForAction(String actionCode);

    /** * 复制 PUBLISHED/ARCHIVED 版本为新 DRAFT（仅超管）。 */
    /** * 同 actionCode 已有 DRAFT 时 409；新草稿版本号 = 同动作 max(version)+1。 */
    SopTemplate copyToDraft(Long id, IpdActor actor);

    /** * 历史恢复：把 ARCHIVED 版本复制为新 DRAFT（仅超管；发布后才重新生效）。 */
    SopTemplate revertToDraft(Long id, IpdActor actor);

    /** * 编辑 DRAFT（仅超管；白名单 title/content；其余字段不可变）。 */
    /** * title 去空格 2-128 字、content 非空 ≥2 字（PARAM_INVALID）； */
    /** * 条件 UPDATE where status=DRAFT 守卫并发（影响 0 行 → 409，零写入）。 */
    SopTemplate updateDraft(Long id, SopTemplateSaveReq req, IpdActor actor);

    /** * 发布 DRAFT（仅超管；BR-IPD-07/AC-IPD-20/AC-IPD-27）。 */
    /** * <p>生物特征动作（bioFeature）正文必须含「算法公平性」与「偏见测试」（缺则 400 零写入）； */
    /** * 同 actionCode 旧 PUBLISHED 条件 UPDATE 归档（effectiveTo=now）； */
    /** * DRAFT→PUBLISHED 条件 UPDATE 守卫并发（0 行 → 409）。 */
    SopTemplate publishDraft(Long id, IpdActor actor);

    /** * 获取模板（任意状态）。service 层内部用法；Controller 暴露时建议只暴露 PUBLISHED。 */
    SopTemplate getById(Long id);

    /** * 实例化模板（MARKET_PM/RD_PM/GROUP_LEADER，BR-IPD-SOP-03）。 */
    /** * <p>同项目同 templateId 下旧 ACTIVE 实例自动 SUPERSEDED；新实例写入 snapshotJson */
    /** * （包含动作列表/责任矩阵/阶段截止日期，序列化当前 ActionCatalog 全集）。 */
    SopTemplateInstance instantiate(Long templateId, Long projectId, IpdActor actor);

    /** * 按项目列出实例（@Transactional readOnly）。项目成员守卫——SUPER_ADMIN 豁免， */
    /** * 非成员/跨租户统一 FORBIDDEN（fail-closed）。 */
    List<SopTemplateInstance> listInstancesByProject(Long projectId, IpdActor actor);

    /** * 手动标记旧实例 SUPERSEDED（SUPER_ADMIN 运维豁免）。 */
    /** * <p>正常实例化流程自动触发；本方法用于模板强制升级或运维介入。 */
    SopTemplateInstance supersedeInstance(Long instanceId, IpdActor actor);

}
