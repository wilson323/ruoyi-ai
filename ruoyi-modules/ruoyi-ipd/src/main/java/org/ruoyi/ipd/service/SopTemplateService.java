package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SOP 模板版本管理与实例快照（P1-3.3，BR-IPD-07）。
 * <p>BR-IPD-07：每个深管动作（actionCode）绑定一份 SOP，同动作下版本号自增，
 * 每次 publish 新版本时旧 PUBLISHED 关闭（effectiveTo=now + status=ARCHIVED），保持线性版本轨迹；
 * 修改后新项目用新版，在研项目保持原版本（AC-IPD-27，快照解耦）。
 * <p>版本机：DRAFT --publish--> PUBLISHED --被新版本替代--> ARCHIVED；
 * 同 actionCode 至多 1 个 PUBLISHED/DRAFT；写路径全程条件 UPDATE（where status=...）守卫并发。
 * <p>AC-IPD-20：publish 时对生物特征动作（ActionCatalog.bioFeature：V10/C12/D11）强制校验
 * content 含「算法公平性」与「偏见测试」，缺则 400。
 * <p>权限：copy/update/publish/revert 仅 SUPER_ADMIN（ipd:sop-template:edit）；
 * 读=内部角色；实例化仅 MARKET_PM/RD_PM/GROUP_LEADER。
 *
 * @author ruoyi-ai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SopTemplateService {

    private final SopTemplateMapper sopTemplateMapper;
    private final SopTemplateInstanceMapper sopTemplateInstanceMapper;
    private final AuditLogService auditLogService;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectMapper projectMapper;

    /** 私有 Jackson 实例：序列化嵌套 JSON（meta/actionList/responsibilityMatrix/phaseDeadlineMap）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    // ========== 版本链（BR-IPD-07：actionCode 维度，读=内部角色，写=SUPER_ADMIN） ==========

    /** 版本列表轻量视图：不拉 mediumtext 正文，CHAR_LENGTH(content) 计算字数；version 倒序。 */
    @Transactional(readOnly = true)
    public List<SopTemplateListItem> listByActionCode(String actionCode) {
        if (actionCode == null || actionCode.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "actionCode 不能为空");
        }
        List<SopTemplate> rows = sopTemplateMapper.selectList(Wrappers.<SopTemplate>query()
            .select("id", "action_code", "title", "version", "status", "CHAR_LENGTH(content) AS content_len")
            .eq("action_code", actionCode.trim())
            .eq("del_flag", "0")
            .orderByDesc("version"));
        return rows.stream()
            .map(t -> new SopTemplateListItem(t.getId(), t.getActionCode(), t.getTitle(),
                t.getVersion(), t.getStatus(), t.getContentLen()))
            .toList();
    }

    /**
     * 当前生效 SOP（PUBLISHED + effectiveTo IS NULL，同 actionCode 下最新一条；含正文）。
     * 不存在时抛 IpdBusinessException(NOT_FOUND)。
     */
    @Transactional(readOnly = true)
    public SopTemplate currentForAction(String actionCode) {
        if (actionCode == null || actionCode.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "actionCode 不能为空");
        }
        List<SopTemplate> actives = sopTemplateMapper.selectList(
            Wrappers.<SopTemplate>lambdaQuery()
                .eq(SopTemplate::getActionCode, actionCode.trim())
                .eq(SopTemplate::getStatus, SopTemplate.Status.PUBLISHED)
                .isNull(SopTemplate::getEffectiveTo)
                .eq(SopTemplate::getDelFlag, "0")
                .orderByDesc(SopTemplate::getVersion)
                .last("LIMIT 1"));
        if (actives.isEmpty() || actives.get(0) == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND,
                "未找到当前生效的 SOP: " + actionCode);
        }
        return actives.get(0);
    }

    /**
     * 复制 PUBLISHED/ARCHIVED 版本为新 DRAFT（仅超管）。
     * 同 actionCode 已有 DRAFT 时 409；新草稿版本号 = 同动作 max(version)+1。
     */
    @Transactional(rollbackFor = Exception.class)
    public SopTemplate copyToDraft(Long id, IpdActor actor) {
        IpdIdorGuard.requireSuperAdmin(actor);
        SopTemplate source = getById(id);
        if (!SopTemplate.Status.PUBLISHED.equals(source.getStatus())
            && !SopTemplate.Status.ARCHIVED.equals(source.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "仅 PUBLISHED/ARCHIVED 版本可复制为草稿，当前状态=" + source.getStatus());
        }
        return duplicateAsDraft(source, actor, "COPY");
    }

    /**
     * 历史恢复：把 ARCHIVED 版本复制为新 DRAFT（仅超管；发布后才重新生效）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SopTemplate revertToDraft(Long id, IpdActor actor) {
        IpdIdorGuard.requireSuperAdmin(actor);
        SopTemplate source = getById(id);
        if (!SopTemplate.Status.ARCHIVED.equals(source.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "仅 ARCHIVED 版本可历史恢复，当前状态=" + source.getStatus());
        }
        return duplicateAsDraft(source, actor, "REVERT");
    }

    /**
     * 编辑 DRAFT（仅超管；白名单 title/content；其余字段不可变）。
     * title 去空格 2-128 字、content 非空 ≥2 字（PARAM_INVALID）；
     * 条件 UPDATE where status=DRAFT 守卫并发（影响 0 行 → 409，零写入）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SopTemplate updateDraft(Long id, SopTemplateSaveReq req, IpdActor actor) {
        IpdIdorGuard.requireSuperAdmin(actor);
        if (req == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "入参不能为空");
        }
        String title = req.title() == null ? "" : req.title().trim();
        String content = req.content() == null ? "" : req.content();
        if (title.length() < 2 || title.length() > 128) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "title 去空格后须 2-128 字");
        }
        if (content.trim().length() < 2) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "content 至少 2 字");
        }
        SopTemplate draft = getById(id);
        if (!SopTemplate.Status.DRAFT.equals(draft.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "仅 DRAFT 可编辑，当前状态=" + draft.getStatus());
        }
        Integer updated = sopTemplateMapper.update(null,
            Wrappers.<SopTemplate>lambdaUpdate()
                .set(SopTemplate::getTitle, title)
                .set(SopTemplate::getContent, content)
                .eq(SopTemplate::getId, id)
                .eq(SopTemplate::getStatus, SopTemplate.Status.DRAFT));
        if (updated == null || updated == 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "草稿已被并发修改，请刷新后重试");
        }
        auditLogService.append(AuditLog.builder()
            .operatorName(actorName(actor)).operatorRole(actor.role())
            .action("UPDATE").entityType("SOP_TEMPLATE").entityId(id)
            .beforeData(AuditEventData.json("title", draft.getTitle(),
                "contentLen", draft.getContent() == null ? 0 : draft.getContent().length()))
            .afterData(AuditEventData.json("title", title, "contentLen", content.length()))
            .reason("P1-3.3 SOP 草稿编辑（diff 不落正文）")
            .build());
        return getById(id);
    }

    /**
     * 发布 DRAFT（仅超管；BR-IPD-07/AC-IPD-20/AC-IPD-27）。
     * <p>生物特征动作（bioFeature）正文必须含「算法公平性」与「偏见测试」（缺则 400 零写入）；
     * 同 actionCode 旧 PUBLISHED 条件 UPDATE 归档（effectiveTo=now）；
     * DRAFT→PUBLISHED 条件 UPDATE 守卫并发（0 行 → 409）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SopTemplate publishDraft(Long id, IpdActor actor) {
        IpdIdorGuard.requireSuperAdmin(actor);
        SopTemplate draft = getById(id);
        if (!SopTemplate.Status.DRAFT.equals(draft.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "仅 DRAFT 可发布，当前状态=" + draft.getStatus());
        }
        requireBioFeatureContent(draft);

        Date now = new Date();
        // 同动作旧 PUBLISHED 逐条条件归档（0 行=已被并发处理，跳过）
        List<SopTemplate> oldPublished = sopTemplateMapper.selectList(
            Wrappers.<SopTemplate>lambdaQuery()
                .eq(SopTemplate::getActionCode, draft.getActionCode())
                .eq(SopTemplate::getStatus, SopTemplate.Status.PUBLISHED)
                .eq(SopTemplate::getDelFlag, "0"));
        for (SopTemplate old : oldPublished) {
            Integer archived = sopTemplateMapper.update(null,
                Wrappers.<SopTemplate>lambdaUpdate()
                    .set(SopTemplate::getStatus, SopTemplate.Status.ARCHIVED)
                    .set(SopTemplate::getEffectiveTo, now)
                    .eq(SopTemplate::getId, old.getId())
                    .eq(SopTemplate::getStatus, SopTemplate.Status.PUBLISHED));
            if (archived != null && archived > 0) {
                auditLogService.append(AuditLog.builder()
                    .operatorName(actorName(actor)).operatorRole(actor.role())
                    .action("ARCHIVE").entityType("SOP_TEMPLATE").entityId(old.getId())
                    .beforeData(AuditEventData.json("status", "PUBLISHED"))
                    .afterData(AuditEventData.json("status", "ARCHIVED", "effectiveTo", now.getTime()))
                    .reason("P1-3.3 SOP 新版本发布，旧版本自动归档")
                    .build());
            }
        }

        // DRAFT→PUBLISHED 条件 UPDATE（守卫并发，0 行 → 409）
        Integer published = sopTemplateMapper.update(null,
            Wrappers.<SopTemplate>lambdaUpdate()
                .set(SopTemplate::getStatus, SopTemplate.Status.PUBLISHED)
                .set(SopTemplate::getEffectiveFrom, now)
                .set(SopTemplate::getEffectiveTo, null)
                .eq(SopTemplate::getId, id)
                .eq(SopTemplate::getStatus, SopTemplate.Status.DRAFT));
        if (published == null || published == 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "草稿已被并发处理，发布失败");
        }
        auditLogService.append(AuditLog.builder()
            .operatorName(actorName(actor)).operatorRole(actor.role())
            .action("PUBLISH").entityType("SOP_TEMPLATE").entityId(id)
            .beforeData(AuditEventData.json("status", "DRAFT"))
            .afterData(AuditEventData.json("status", "PUBLISHED",
                "version", draft.getVersion(),
                "contentLen", draft.getContent() == null ? 0 : draft.getContent().length()))
            .reason("P1-3.3 SOP 草稿发布，仅影响此后实例化的项目")
            .build());
        return getById(id);
    }

    /** 内部：AC-IPD-20 生物特征动作发布校验（缺关键词 → 400，零写入）。 */
    private static void requireBioFeatureContent(SopTemplate draft) {
        boolean bioFeature = ActionCatalog.ALL.stream()
            .anyMatch(def -> def.bioFeature() && def.code().equals(draft.getActionCode()));
        if (!bioFeature) {
            return;
        }
        String content = draft.getContent() == null ? "" : draft.getContent();
        if (!content.contains("算法公平性") || !content.contains("偏见测试")) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "生物特征动作（如 V10/C12/D11）的 SOP 正文必须包含「算法公平性」与「偏见测试」要求（AC-IPD-20）");
        }
    }

    /** 内部：复制 PUBLISHED/ARCHIVED 为新 DRAFT（版本号=同动作 max+1；审计 COPY/REVERT）。 */
    private SopTemplate duplicateAsDraft(SopTemplate source, IpdActor actor, String auditAction) {
        List<SopTemplate> existingDrafts = sopTemplateMapper.selectList(
            Wrappers.<SopTemplate>lambdaQuery()
                .eq(SopTemplate::getActionCode, source.getActionCode())
                .eq(SopTemplate::getStatus, SopTemplate.Status.DRAFT)
                .eq(SopTemplate::getDelFlag, "0"));
        if (!existingDrafts.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "同一动作同时只能有一个草稿，请先编辑或发布现有草稿");
        }
        long maxVersion = 0L;
        List<SopTemplate> all = sopTemplateMapper.selectList(
            Wrappers.<SopTemplate>lambdaQuery()
                .eq(SopTemplate::getActionCode, source.getActionCode())
                .eq(SopTemplate::getDelFlag, "0"));
        for (SopTemplate t : all) {
            if (t.getVersion() != null && t.getVersion() > maxVersion) {
                maxVersion = t.getVersion();
            }
        }
        SopTemplate draft = SopTemplate.builder()
            .actionCode(source.getActionCode())
            .title(source.getTitle())
            .content(source.getContent())
            .templateCode(source.getTemplateCode())
            .templateName(source.getTemplateName())
            .description(source.getDescription())
            .category(source.getCategory())
            .version(maxVersion + 1)
            .status(SopTemplate.Status.DRAFT)
            .createdBy(actor.id() == null ? null : actor.id().toString())
            .tenantId(source.getTenantId())
            .delFlag("0")
            .build();
        sopTemplateMapper.insert(draft);
        if (draft.getId() == null || draft.getId() <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR, "SOP 草稿写入失败：未生成主键");
        }
        auditLogService.append(AuditLog.builder()
            .operatorName(actorName(actor)).operatorRole(actor.role())
            .action(auditAction).entityType("SOP_TEMPLATE").entityId(draft.getId())
            .beforeData(AuditEventData.json("sourceId", source.getId(),
                "sourceVersion", source.getVersion(), "sourceStatus", source.getStatus()))
            .afterData(AuditEventData.json("version", draft.getVersion(), "status", "DRAFT",
                "contentLen", draft.getContent() == null ? 0 : draft.getContent().length()))
            .reason("P1-3.3 SOP " + ("COPY".equals(auditAction) ? "复制为草稿" : "历史恢复为草稿"))
            .build());
        return draft;
    }

    /**
     * 获取模板（任意状态）。service 层内部用法；Controller 暴露时建议只暴露 PUBLISHED。
     */
    @Transactional(readOnly = true)
    public SopTemplate getById(Long id) {
        if (id == null || id <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "id 非法");
        }
        SopTemplate t = sopTemplateMapper.selectById(id);
        if (t == null || "1".equals(t.getDelFlag())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "SOP 模板不存在: " + id);
        }
        return t;
    }

    // ========== 实例快照（MARKET_PM/RD_PM/GROUP_LEADER） ==========

    /**
     * 实例化模板（MARKET_PM/RD_PM/GROUP_LEADER，BR-IPD-SOP-03）。
     * <p>同项目同 templateId 下旧 ACTIVE 实例自动 SUPERSEDED；新实例写入 snapshotJson
     * （包含动作列表/责任矩阵/阶段截止日期，序列化当前 ActionCatalog 全集）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SopTemplateInstance instantiate(Long templateId, Long projectId, IpdActor actor) {
        IpdIdorGuard.requireAuthenticated(actor);
        if (actor.role() == null
            || !(actor.role().equals("MARKET_PM")
                || actor.role().equals("RD_PM")
                || actor.role().equals("GROUP_LEADER")
                || actor.role().equals("SUPER_ADMIN"))) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN,
                "实例化 SOP 模板仅 MARKET_PM/RD_PM/GROUP_LEADER 可操作");
        }
        if (templateId == null || templateId <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "templateId 非法");
        }
        if (projectId == null || projectId <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 非法");
        }
        // 项目成员 + 跨租户守卫（SUPER_ADMIN 绕过；非成员/跨租户统一 FORBIDDEN，fail-closed 不泄漏存在性）
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(
            actor, projectId, projectMemberMapper, projectMapper);

        SopTemplate template = getById(templateId);
        if (!SopTemplate.Status.PUBLISHED.equals(template.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "仅 PUBLISHED 模板可实例化，当前状态=" + template.getStatus());
        }

        // 关闭同项目同 templateId 旧 ACTIVE 实例
        List<SopTemplateInstance> existingActives = sopTemplateInstanceMapper.selectList(
            Wrappers.<SopTemplateInstance>lambdaQuery()
                .eq(SopTemplateInstance::getProjectId, projectId)
                .eq(SopTemplateInstance::getTemplateId, templateId)
                .eq(SopTemplateInstance::getStatus, SopTemplateInstance.Status.ACTIVE)
                .eq(SopTemplateInstance::getDelFlag, "0"));
        for (SopTemplateInstance old : existingActives) {
            supersedeInstanceInternal(old, actor);
        }

        // 计算新实例版本（同项目同 templateId 下自增 1）
        long maxVersion = 0L;
        List<SopTemplateInstance> allByTemplateAndProject = sopTemplateInstanceMapper.selectList(
            Wrappers.<SopTemplateInstance>lambdaQuery()
                .eq(SopTemplateInstance::getProjectId, projectId)
                .eq(SopTemplateInstance::getTemplateId, templateId)
                .eq(SopTemplateInstance::getDelFlag, "0"));
        for (SopTemplateInstance i : allByTemplateAndProject) {
            if (i.getInstanceVersion() != null && i.getInstanceVersion() > maxVersion) {
                maxVersion = i.getInstanceVersion();
            }
        }
        long nextInstanceVersion = maxVersion + 1;

        Date now = new Date();
        String snapshotJson = buildSnapshotJson(template);

        SopTemplateInstance fresh = SopTemplateInstance.builder()
            .templateId(templateId)
            .instanceVersion(nextInstanceVersion)
            .projectId(projectId)
            .snapshotJson(snapshotJson)
            .instantiatedAt(now)
            .instantiatedBy(actor.id() == null ? null : actor.id().toString())
            .status(SopTemplateInstance.Status.ACTIVE)
            .tenantId(template.getTenantId())
            .delFlag("0")
            .build();
        sopTemplateInstanceMapper.insert(fresh);
        if (fresh.getId() == null || fresh.getId() <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR, "SOP 实例写入失败：未生成主键");
        }
        auditLogService.append(AuditLog.builder()
            .operatorName(actorName(actor)).operatorRole(actor.role())
            .action("INSTANTIATE").entityType("SOP_TEMPLATE_INSTANCE").entityId(fresh.getId())
            .afterData(AuditEventData.json(
                "templateId", templateId,
                "projectId", projectId,
                "instanceVersion", nextInstanceVersion))
            .reason("P1-3.3 SOP 模板实例化快照")
            .build());
        return fresh;
    }

    /**
     * 按项目列出实例（@Transactional readOnly）。项目成员守卫——SUPER_ADMIN 豁免，
     * 非成员/跨租户统一 FORBIDDEN（fail-closed）。
     */
    @Transactional(readOnly = true)
    public List<SopTemplateInstance> listInstancesByProject(Long projectId, IpdActor actor) {
        if (projectId == null || projectId <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 非法");
        }
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(
            actor, projectId, projectMemberMapper, projectMapper);
        return sopTemplateInstanceMapper.selectList(
            Wrappers.<SopTemplateInstance>lambdaQuery()
                .eq(SopTemplateInstance::getProjectId, projectId)
                .eq(SopTemplateInstance::getDelFlag, "0")
                .orderByDesc(SopTemplateInstance::getInstanceVersion));
    }

    /**
     * 手动标记旧实例 SUPERSEDED（SUPER_ADMIN 运维豁免）。
     * <p>正常实例化流程自动触发；本方法用于模板强制升级或运维介入。
     */
    @Transactional(rollbackFor = Exception.class)
    public SopTemplateInstance supersedeInstance(Long instanceId, IpdActor actor) {
        IpdIdorGuard.requireSuperAdmin(actor);
        if (instanceId == null || instanceId <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "instanceId 非法");
        }
        SopTemplateInstance inst = sopTemplateInstanceMapper.selectById(instanceId);
        if (inst == null || "1".equals(inst.getDelFlag())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "SOP 实例不存在: " + instanceId);
        }
        if (SopTemplateInstance.Status.SUPERSEDED.equals(inst.getStatus())
            || SopTemplateInstance.Status.ARCHIVED.equals(inst.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "实例已终态，不可再次 SUPERSEDED: status=" + inst.getStatus());
        }
        supersedeInstanceInternal(inst, actor);
        return sopTemplateInstanceMapper.selectById(instanceId);
    }

    /**
     * 内部：标记实例为 SUPERSEDED 并写审计（无权限校验，调用方已校验）。
     */
    private void supersedeInstanceInternal(SopTemplateInstance inst, IpdActor actor) {
        String beforeStatus = inst.getStatus();
        inst.setStatus(SopTemplateInstance.Status.SUPERSEDED);
        sopTemplateInstanceMapper.updateById(inst);
        auditLogService.append(AuditLog.builder()
            .operatorName(actorName(actor)).operatorRole(actor == null ? "SYSTEM" : actor.role())
            .action("SUPERSEDE").entityType("SOP_TEMPLATE_INSTANCE").entityId(inst.getId())
            .beforeData(AuditEventData.json("status", beforeStatus))
            .afterData(AuditEventData.json("status", "SUPERSEDED"))
            .reason("P1-3.3 SOP 实例被新版本替换")
            .build());
    }

    // ========== helpers ==========

    /**
     * 序列化快照 JSON（BR-IPD-SOP-03）：meta + actionList + responsibilityMatrix + phaseDeadlineMap。
     * <p>使用私有 ObjectMapper 序列化嵌套结构，避免手工拼接出现非法 JSON。
     * ActionCatalog 按 category 过滤：DEEP_MGMT 仅 DEEP、LIGHT_MGMT 仅 LIGHT、MIXED 全留。
     */
    private static String buildSnapshotJson(SopTemplate template) {
        // 按 category 过滤
        String filterDepth = null;
        if (SopTemplate.Category.DEEP_MGMT.equals(template.getCategory())) {
            filterDepth = "DEEP";
        } else if (SopTemplate.Category.LIGHT_MGMT.equals(template.getCategory())) {
            filterDepth = "LIGHT";
        }

        List<Map<String, String>> actionList = new ArrayList<>();
        Map<String, String> responsibilityMatrix = new LinkedHashMap<>();
        for (ActionDef def : ActionCatalog.ALL) {
            if (filterDepth != null && !filterDepth.equals(def.depth())) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("code", def.code());
            row.put("name", def.name());
            row.put("stage", def.stage());
            row.put("ownerRole", def.ownerRole());
            row.put("depth", def.depth());
            actionList.add(row);
            responsibilityMatrix.put(def.code(), def.ownerRole());
        }

        Map<String, Integer> phaseDeadlineMap = new LinkedHashMap<>();
        phaseDeadlineMap.put("CONCEPT", 30);
        phaseDeadlineMap.put("PLAN", 60);
        phaseDeadlineMap.put("DEV", 120);
        phaseDeadlineMap.put("VALID", 90);
        phaseDeadlineMap.put("LAUNCH", 30);
        phaseDeadlineMap.put("LIFECYCLE", 180);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("templateCode", template.getTemplateCode());
        meta.put("templateName", template.getTemplateName());
        meta.put("version", template.getVersion());
        meta.put("category", template.getCategory());
        meta.put("actionCount", actionList.size());
        meta.put("phaseDeadlineMap", phaseDeadlineMap);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta);
        root.put("actionList", actionList);
        root.put("responsibilityMatrix", responsibilityMatrix);
        root.put("phaseDeadlineMap", phaseDeadlineMap);

        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "SOP 快照 JSON 序列化失败: " + e.getMessage());
        }
    }

    private static String actorName(IpdActor actor) {
        if (actor == null) return "SYSTEM";
        return actor.name() == null ? (actor.id() == null ? "SYSTEM" : actor.id().toString()) : actor.name();
    }
}