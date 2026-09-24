package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.CertTemplate;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectCertItem;
import org.ruoyi.ipd.dto.ProjectCertListView;
import org.ruoyi.ipd.dto.ProjectCertManualReq;
import org.ruoyi.ipd.mapper.ProjectCertItemMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.seed.MarketCodeResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P1-7.1：目标市场认证清单落项目并保留版本（AC-PROD-10/11/12）。
 * <ul>
 *   <li>按市场码从 cert_templates 带出；同国多认证并列去重</li>
 *   <li>re-sync 只增不删；DONE 状态永不被静默重置</li>
 *   <li>未知市场 token 返回给前端提示手工补充</li>
 * </ul>
 * Round 8 / R8-P0-6：syncFromProject 批量化（一次性 selectList + insertBatch）
 * Round 8 / R8-P0-9：changeStatus 加乐观锁（@Version）
 */
@Service
@RequiredArgsConstructor
public class ProjectCertServiceImpl implements IProjectCertService {

    private static final Set<String> STATUSES = Set.of("PENDING", "IN_PROGRESS", "DONE", "NA");

    private final ProjectCertItemMapper projectCertItemMapper;
    private final ProjectMapper projectMapper;
    private final CertTemplateService certTemplateService;
    private final IAuditLogService auditLogService;

    /**
     * 按项目当前目标市场同步 AUTO 项（批量化版本）。
     *
     * <p>Round 8 / R8-P0-6：从「循环每项 selectCount + insert」改造为「一次性 selectList in(...)
     *  + insertBatch(200)」。单项目典型 33 项从 66 IO 降到 2 IO（一个 selectList + 一个 insertBatch）。
     *
     * @param project    已落库项目（含 targetMarkets）
     * @param operatorId 操作人
     * @return 新增条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int syncFromProject(Project project, Long operatorId) {
        if (project == null || project.getId() == null) {
            throw new ServiceException("项目不存在");
        }
        List<String> codes = MarketCodeResolver.knownCodes(project.getTargetMarkets());
        if (codes.isEmpty()) {
            return 0;
        }
        List<CertTemplate> templates = certTemplateService.resolve(codes.toArray(String[]::new));
        if (templates.isEmpty()) {
            return 0;
        }
        String catalogVersion = buildCatalogVersion(templates);
        // R8-P0-6：一次性查所有已存在的 (countryCode|certName) 集合
        Set<String> existingKeys = listExistingKeys(project.getId(), templates);
        Date now = new Date();
        List<ProjectCertItem> toInsert = new ArrayList<>();
        for (CertTemplate t : templates) {
            String key = t.getCountryCode() + "|" + t.getCertName();
            if (existingKeys.contains(key)) {
                continue;
            }
            ProjectCertItem item = ProjectCertItem.builder()
                .projectId(project.getId())
                .templateId(t.getId())
                .countryCode(t.getCountryCode())
                .countryName(t.getCountryName())
                .certName(t.getCertName())
                .certAuthority(t.getCertAuthority())
                .requirementDesc(t.getRequirementDesc())
                .isMandatory(t.getIsMandatory() == null ? "1" : t.getIsMandatory())
                .source("AUTO")
                .status("PENDING")
                .catalogVersion(catalogVersion)
                .tenantId("000000")
                .delFlag("0")
                .version(0)
                .build();
            item.setCreateTime(now);
            item.setCreateBy(operatorId);
            item.setUpdateBy(operatorId);
            toInsert.add(item);
        }
        int added = toInsert.size();
        if (added > 0) {
            // R8-P0-6：批量插入（MyBatis-Plus insertBatch 自动分批）
            projectCertItemMapper.insertBatch(toInsert, 200);
            auditLogService.append(AuditLog.builder()
                .operatorId(operatorId).action("PROJECT_CERT_SYNC")
                .entityType("project_cert_items").entityId(project.getId())
                .reason("catalog=" + catalogVersion + ";added=" + added)
                .createTime(now).build());
        }
        return added;
    }

    /**
     * R212-⑤（看板卡 dbe1b6a7）：HTTP re-sync 入口专用重载——补操作人组归属断言。
     *
     * <p>命名：刻意不叫 {@code syncFromProject} 重载——(Project, IpdActor) 与 (Project, Long)
     * 同 arity 重载会让既有 {@code when(certs.syncFromProject(any(), any()))} 桩位产生
     * 编译期歧义（P131 集成测试），异名即零波及。
     *
     * <p>原两参 {@link #syncFromProject(Project, Long)} 只用 operatorId 落审计、不做任何
     * 归属校验，控制器 {@code projectService.getById(id)} 可取任意项目 ⇒ 跨组写入认证清单。
     * 本重载把「操作人组 == 项目主组」断言前置到任何写入之前（SUPER_ADMIN 豁免）。
     *
     * <p>为什么不把断言塞进两参版本：两参版还有第二个生产调用方
     * {@code ProjectService.insertNewProject}（立项 bootstrap），彼处的组语义由
     * {@code Project.create} 的 fallbackMainGroupId 规则（BR-ORG-01：组长可代本组立项、
     * 客户端可显式选主组）单独负责，混入断言会改变立项语义。故两参版保持原样并退化为
     * 「内部/已鉴权上下文」专用，HTTP 面一律走本重载。
     *
     * @param project 已加载项目（为 null 时由被委托方按既有「项目不存在」抛出，文案不变）
     * @param actor   操作人会话身份（必填）
     * @return 新增条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int syncFromProjectAuthorized(Project project, org.ruoyi.ipd.security.IpdActor actor) {
        org.ruoyi.ipd.security.IpdIdorGuard.requireAuthenticated(actor);
        if (project != null) {
            org.ruoyi.ipd.security.IpdIdorGuard.assertSameGroupIpd(actor, project.getMainGroupId());
        }
        return syncFromProject(project, actor.id());
    }

    /**
     * R8-P0-6：一次性 selectList 取出该项目全部已存在的 (countryCode|certName) 集合。
     * @TableLogic 启用后 delFlag=1 自动过滤，无需手写。
     */
    private Set<String> listExistingKeys(Long projectId, List<CertTemplate> templates) {
        if (templates.isEmpty()) {
            return Set.of();
        }
        List<String> countryCodes = templates.stream()
            .map(CertTemplate::getCountryCode).distinct().toList();
        List<ProjectCertItem> existing = projectCertItemMapper.selectList(new LambdaQueryWrapper<ProjectCertItem>()
            .select(ProjectCertItem::getCountryCode, ProjectCertItem::getCertName)
            .eq(ProjectCertItem::getProjectId, projectId)
            .in(ProjectCertItem::getCountryCode, countryCodes));
        Set<String> keys = new HashSet<>(existing.size());
        for (ProjectCertItem i : existing) {
            keys.add(i.getCountryCode() + "|" + i.getCertName());
        }
        return keys;
    }

    /**
     * 列表视图：清单 + 未知市场提示 + 最近 catalogVersion。
     *
     * @param projectId 项目
     * @return 视图
     */
    public ProjectCertListView listView(Long projectId) {
        Project project = requireProject(projectId);
        List<ProjectCertItem> items = listByProject(projectId);
        String version = items.stream()
            .filter(i -> "AUTO".equals(i.getSource()))
            .map(ProjectCertItem::getCatalogVersion)
            .filter(v -> v != null && !v.isBlank())
            .findFirst().orElse(null);
        return new ProjectCertListView(
            projectId, version,
            MarketCodeResolver.unknownTokens(project.getTargetMarkets()),
            items);
    }

    public List<ProjectCertItem> listByProject(Long projectId) {
        // R8-P0-8：@TableLogic 启用后无需手写 .eq(delFlag, "0")
        return projectCertItemMapper.selectList(new LambdaQueryWrapper<ProjectCertItem>()
            .eq(ProjectCertItem::getProjectId, projectId)
            .orderByDesc(ProjectCertItem::getIsMandatory)
            .orderByAsc(ProjectCertItem::getCountryCode)
            .orderByAsc(ProjectCertItem::getId));
    }

    /**
     * AC-PROD-12：手工补充认证项。
     *
     * @param projectId  项目
     * @param req        白名单请求
     * @param operatorId 操作人
     * @return 新建项
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectCertItem addManual(Long projectId, ProjectCertManualReq req, Long operatorId) {
        requireProject(projectId);
        if (req == null || isBlank(req.countryCode()) || isBlank(req.countryName()) || isBlank(req.certName())) {
            throw new ServiceException("countryCode/countryName/certName 必填");
        }
        String code = req.countryCode().trim().toUpperCase();
        String name = req.certName().trim();
        if (existsLive(projectId, code, name)) {
            throw new ServiceException("项目已存在同名认证项: " + code + "/" + name);
        }
        ProjectCertItem item = ProjectCertItem.builder()
            .projectId(projectId)
            .templateId(null)
            .countryCode(code)
            .countryName(req.countryName().trim())
            .certName(name)
            .certAuthority(req.certAuthority())
            .isMandatory(isBlank(req.isMandatory()) ? "1" : req.isMandatory().trim())
            .source("MANUAL")
            .status("PENDING")
            .catalogVersion("manual")
            .tenantId("000000")
            .delFlag("0")
            .version(0)
            .build();
        item.setCreateTime(new Date());
        item.setCreateBy(operatorId);
        item.setUpdateBy(operatorId);
        projectCertItemMapper.insert(item);
        auditLogService.append(AuditLog.builder()
            .operatorId(operatorId).action("PROJECT_CERT_MANUAL")
            .entityType("project_cert_items").entityId(item.getId())
            .reason(code + "/" + name)
            .createTime(new Date()).build());
        return item;
    }

    /**
     * 更新清单项状态；DONE 后 re-sync 不会改回 PENDING。
     * Round 8 / R8-P0-9：加 @Version 乐观锁（entity.version 自动比对）。
     *
     * @param projectId  项目
     * @param itemId     清单项
     * @param target     目标状态
     * @param operatorId 操作人
     * @return 更新后项
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectCertItem changeStatus(Long projectId, Long itemId, String target, Long operatorId) {
        if (target == null || !STATUSES.contains(target)) {
            throw new ServiceException("非法认证状态: " + target);
        }
        ProjectCertItem item = projectCertItemMapper.selectById(itemId);
        // R8-P0-8：@TableLogic 自动过滤 delFlag=1，无需手写
        if (item == null || !projectId.equals(item.getProjectId())) {
            throw new ServiceException("认证清单项不存在");
        }
        String before = item.getStatus();
        if (target.equals(before)) {
            return item;
        }
        item.setStatus(target);
        item.setUpdateBy(operatorId);
        // R8-P0-9：@Version 乐观锁——updateById 返回 0 即并发冲突
        int updated = projectCertItemMapper.updateById(item);
        if (updated == 0) {
            throw new ServiceException("认证状态变更被并发覆盖（version 不匹配），请刷新后重试");
        }
        auditLogService.append(AuditLog.builder()
            .operatorId(operatorId).action("PROJECT_CERT_STATUS")
            .entityType("project_cert_items").entityId(itemId)
            .reason(before + "→" + target)
            .createTime(new Date()).build());
        return item;
    }

    private boolean existsLive(Long projectId, String countryCode, String certName) {
        Long n = projectCertItemMapper.selectCount(new LambdaQueryWrapper<ProjectCertItem>()
            .eq(ProjectCertItem::getProjectId, projectId)
            .eq(ProjectCertItem::getCountryCode, countryCode)
            .eq(ProjectCertItem::getCertName, certName));
        return n != null && n > 0;
    }

    private Project requireProject(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null || "1".equals(p.getDelFlag())) {
            throw new ServiceException("项目不存在: " + projectId);
        }
        return p;
    }

    private static String buildCatalogVersion(List<CertTemplate> templates) {
        String ids = templates.stream().map(CertTemplate::getId).sorted()
            .map(String::valueOf).collect(Collectors.joining(","));
        return "tpl-" + templates.size() + "-" + Integer.toHexString(ids.hashCode());
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
