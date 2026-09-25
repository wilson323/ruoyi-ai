package org.ruoyi.ipd.service;

import cn.hutool.crypto.digest.DigestUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Deliverable;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * P1-4.2（卡 fde68b8c）：真实附件上传下载和动作归属鉴权。
 *
 * <p>卡面 5 项 AC 逐条落点：
 * <ol>
 *   <li>AC1 对象存在才登记——服务端上传成功后以 {@link ISysOssService#getById(Long)} 回读对象登记
 *       （与 GateElementResultController「ossId 由服务端解析、禁客户端透传」同口径），回读为空即拒绝登记；</li>
 *   <li>AC2 100MB 与格式策略——TS14 文件约束「单文件 ≤ 100MB，支持 doc/xls/ppt/pdf/img/zip」，
 *       在触达对象存储<b>之前</b>前置拒绝（超长不上传）；</li>
 *   <li>AC3 上传者/大小/hash 入库——uploadedBy 取服务端会话 actor.id()（不信任请求体）、
 *       fileSize 取 multipart 实际字节数、contentHash 对文件字节计算 SHA-256 hex
 *       （列由 2026-09-25 migration 落 ipd_dev，历史行 NULL）；</li>
 *   <li>AC4 下载校验项目归属——{@link IpdIdorGuard#requireProjectMemberOrSuperAdmin} 守卫 3
 *       同口径（在职成员或 SUPER_ADMIN，fail-closed 不泄漏存在性）；</li>
 *   <li>AC5 失败孤儿补偿——对象已上传但后续登记失败（回读不存在 / insert 抛错）时，
 *       补偿删除该 OSS 对象（尽力而为，补偿失败不掩盖原始失败），不留存储侧孤儿。</li>
 * </ol>
 *
 * <p>与相邻契约的边界：既有 {@code StageActionService.addDeliverable(actionId, fileName, ossId, operator)}
 * （CONSISTENCY-13 客户端 ossId 回填链）保留不动；本服务为卡面「真实上传下载」新增服务端可信链入口。
 */
@Service
@RequiredArgsConstructor
public class DeliverableService implements IDeliverableService {

    /** AC2：单文件上限 100MB（TS14 文件约束「单文件 ≤ 100MB」） */
    public static final long MAX_UPLOAD_BYTES = 100L * 1024 * 1024;

    /**
     * AC2 格式策略（TS14「doc/xls/ppt/pdf/img/zip」的可扩展名映射）：
     * Office 系含 2007+ 扩展（docx/xlsx/pptx），img 含常见栅格格式，小写比较。
     */
    static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf",
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "zip");

    private final DeliverableMapper deliverableMapper;
    private final StageActionMapper stageActionMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ISysOssService ossService;
    private final IAuditLogService auditLogService;

    /**
     * AC1/AC2/AC3/AC5 编排：先格式与大小前置校验 → 动作/项目门禁（口径同
     * {@link StageActionService#addDeliverable}：项目软删拒绝、暂停/归档只读）→
     * 服务端上传 → 对象存在回读 → SHA-256 落库登记 → 任一步失败补偿孤儿对象。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Deliverable upload(Long actionId, MultipartFile file, IpdActor actor) {
        IpdIdorGuard.requireAuthenticated(actor);
        if (actionId == null) {
            throw new ServiceException("动作实例ID不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new ServiceException("上传文件不能为空");
        }
        // AC2：大小限制前置拒绝，超大文件不触达对象存储
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ServiceException("附件超过单文件 100MB 上限（TS14 文件约束）: " + file.getSize() + " bytes");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new ServiceException("附件缺少文件名，无法判定格式");
        }
        // AC2：格式策略白名单
        String extension = extensionOf(fileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ServiceException("附件格式不在策略白名单（doc/xls/ppt/pdf/img/zip，TS14 文件约束）: " + extension);
        }
        StageAction a = stageActionMapper.selectById(actionId);
        if (a == null) {
            throw new ServiceException("动作实例不存在: " + actionId);
        }
        assertProjectWritable(a.getProjectId());

        SysOssVo uploaded = ossService.upload(file);
        if (uploaded == null || uploaded.getOssId() == null) {
            throw new ServiceException("对象存储上传失败（未返回 ossId），不登记交付物");
        }
        // AC1：对象存在才登记——服务端按 ossId 回读，登记链不信任客户端传入
        SysOssVo stored = ossService.getById(uploaded.getOssId());
        if (stored == null) {
            compensateOrphan(uploaded.getOssId());
            throw new ServiceException("OSS 对象登记不存在，拒绝登记交付物（对象存在才登记）: ossId=" + uploaded.getOssId());
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            compensateOrphan(uploaded.getOssId());
            throw new ServiceException("读取上传文件失败: " + ex.getMessage());
        }
        Deliverable d = Deliverable.builder()
            .actionId(actionId)
            .projectId(a.getProjectId())
            .fileName(fileName)
            .ossId(uploaded.getOssId())
            .fileUrl(stored.getUrl())
            // AC3：大小/上传者/SHA-256 hash 均取服务端可信来源入库
            .fileSize(file.getSize())
            .uploadedBy(actor.id())
            .uploadedAt(new Date())
            .contentHash(DigestUtil.sha256Hex(bytes))
            .build();
        try {
            deliverableMapper.insert(d);
        } catch (RuntimeException failure) {
            // AC5：登记失败 ⇒ 补偿删除已上传对象，不留孤儿
            compensateOrphan(d.getOssId());
            throw failure;
        }
        auditLogService.append(AuditLog.builder()
            .operatorName(String.valueOf(actor.id())).operatorRole(actor.role())
            .action("CREATE").entityType("DELIVERABLE").entityId(d.getId())
            .afterData(AuditEventData.json(
                "actionId", actionId,
                "fileName", fileName,
                "ossId", d.getOssId(),
                "fileSize", d.getFileSize(),
                "contentHash", d.getContentHash()))
            .build());
        return d;
    }

    /**
     * AC4：下载校验项目归属——交付物所属项目的在职成员或 SUPER_ADMIN 才放行，
     * 守卫失败 fail-closed（统一 FORBIDDEN 文案，不泄漏他组资源存在性）。
     */
    @Override
    public void download(Long deliverableId, IpdActor actor, HttpServletResponse response) throws IOException {
        IpdIdorGuard.requireAuthenticated(actor);
        if (deliverableId == null) {
            throw new ServiceException("交付物ID不能为空");
        }
        Deliverable d = deliverableMapper.selectById(deliverableId);
        if (d == null) {
            throw new ServiceException("交付物不存在: " + deliverableId);
        }
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(actor, d.getProjectId(), projectMemberMapper, projectMapper);
        if (d.getOssId() == null) {
            throw new ServiceException("交付物未关联对象存储文件，禁止下载（P1-4.2 可信链要求）: " + deliverableId);
        }
        ossService.download(d.getOssId(), response);
    }

    /** 小写扩展名（无点号）；无扩展名返回空串，必然不命中白名单。 */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * AC5：孤儿补偿——删除登记失败时已上传的 OSS 对象。尽力而为：补偿自身失败仅吞掉，
     * 不掩盖原始失败（与仓内「补偿失败不二次抛出」惯例一致）；未被补偿的残留面由巡检承接
     * （卡面残余「崩溃自动孤儿巡检」不在本卡 5 项 AC 内）。
     */
    private void compensateOrphan(Long ossId) {
        if (ossId == null) {
            return;
        }
        try {
            ossService.deleteWithValidByIds(List.of(ossId), false);
        } catch (RuntimeException ignored) {
            // 补偿失败不掩盖原始失败
        }
    }

    /** 项目状态门禁（口径与 StageActionService.assertProjectWritable 一致：不存在/软删拒绝，暂停/归档只读）。 */
    private void assertProjectWritable(Long projectId) {
        if (projectId == null) {
            return;
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null || "1".equals(project.getDelFlag())) {
            throw new ServiceException("项目不存在: " + projectId);
        }
        if ("SUSPENDED".equals(project.getStatus()) || "ARCHIVED".equals(project.getStatus())) {
            throw new ServiceException("暂停/归档项目禁止变更动作状态");
        }
    }
}
