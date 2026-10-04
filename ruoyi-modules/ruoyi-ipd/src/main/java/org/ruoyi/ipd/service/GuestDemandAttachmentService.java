package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.dto.PortalDemandTraceView;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** 原需求附件：上传能力与查询码分离；文件下载必须另过真实 Person 对象权限。 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class GuestDemandAttachmentService {
    @org.springframework.beans.factory.annotation.Value("${ipd.demand.attachments.storage-config-key:ipd-demand-private}")
    private String storageConfigKey = "ipd-demand-private";
    private final RequirementMapper mapper;
    private final ISysOssService oss;
    private final IpdPermission permission;
    private final ProductLineMapper lines;
    private final org.ruoyi.ipd.mapper.ProductLineMemberMapper lineMembers;
    private org.ruoyi.ipd.security.IpdAuthSession authSession;
    /** 必需的真实 IPD Person 会话；与平台 User 租户会话分开。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void setAuthSession(org.ruoyi.ipd.security.IpdAuthSession authSession) { this.authSession = Objects.requireNonNull(authSession); }
    private Clock clock = Clock.systemUTC();
    /** 生产可接既有时钟 bean，测试固定时刻验证真实上传期限；旧构造保持兼容。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClock(Clock clock) { this.clock = Objects.requireNonNull(clock); }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "mp4", "mov", "avi", "mkv");
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    public record StoredAttachment(String key, String fileName, long fileSize, Long ossId, String sha256) { }
    /** 内部清单不泄露 OSS 地址；下载仍须走下方对象校验。 */
    public record AttachmentView(String key, String fileName, long fileSize) { }
    public static String newUploadToken() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String tokenHash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    static List<StoredAttachment> stored(Requirement requirement) {
        if (requirement.getAttachmentsJson() == null || requirement.getAttachmentsJson().isBlank()) return List.of();
        try {
            List<StoredAttachment> result = JSON.readValue(requirement.getAttachmentsJson(), new TypeReference<List<StoredAttachment>>() {});
            if (result == null) throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR);
            return result;
        } catch (IOException failure) { throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR); }
    }
    public static List<PortalDemandTraceView.AttachmentEntry> publicEntries(Requirement requirement) {
        return stored(requirement).stream().map(file -> new PortalDemandTraceView.AttachmentEntry(file.fileName(), file.fileSize())).toList();
    }
    @Transactional(rollbackFor = Exception.class)
    public AttachmentView upload(String code, String token, String key, MultipartFile file) throws IOException {
        if (code == null || !GuestDemandService.QUERY_CODE_PATTERN.matcher(code).matches()
            || token == null || token.length() != 43 || key == null || !key.matches("[a-zA-Z0-9_-]{8,64}")) throw invalid();
        // 游客门户没有内部 Person 会话；与既有提交落库默认一致，限定服务端目录租户。
        Requirement requirement = mapper.selectOne(new LambdaQueryWrapper<Requirement>().eq(Requirement::getQueryCode, code).apply("tenant_id = {0}", org.ruoyi.common.core.constant.TenantConstants.DEFAULT_TENANT_ID).last("FOR UPDATE"));
        if (requirement == null || requirement.getUploadTokenHash() == null
            || !MessageDigest.isEqual(requirement.getUploadTokenHash().getBytes(StandardCharsets.US_ASCII), tokenHash(token).getBytes(StandardCharsets.US_ASCII))) throw denied();
        if (!"SUBMITTED".equals(requirement.getStatus()) || requirement.getCreateTime() == null
            || clock.millis() >= requirement.getCreateTime().getTime() + 24L * 3600 * 1000) throw denied();
        if (file == null || file.isEmpty()) throw invalid();
        if (file.getSize() > MAX_BYTES) throw new IpdBusinessException(ApiV1ErrorCode.ATTACHMENT_TOO_LARGE);
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(c -> c < 32)) throw invalid();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) throw invalid();
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes())); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        List<StoredAttachment> entries = new ArrayList<>(stored(requirement));
        for (StoredAttachment entry : entries) if (entry.key().equals(key)) {
            if (!entry.sha256().equals(digest) || !entry.fileName().equals(name) || entry.fileSize() != file.getSize()) throw invalid();
            return view(entry);
        }
        if (entries.size() >= 5) throw new IpdBusinessException(ApiV1ErrorCode.ATTACHMENT_TOO_LARGE);
        SysOssVo uploaded = oss.uploadPrivate(file, storageConfigKey);
        if (uploaded == null || uploaded.getOssId() == null || uploaded.getFileName() == null || !storageConfigKey.equals(uploaded.getService())) throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR);
        Long ossId = uploaded.getOssId();
        if (oss.getPrivateById(ossId, storageConfigKey) == null) {
            cleanup(uploaded);
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) cleanup(uploaded);
            }
        });
        StoredAttachment entry = new StoredAttachment(key, name, file.getSize(), ossId, digest);
        entries.add(entry);
        try {
            requirement.setAttachmentsJson(JSON.writeValueAsString(entries));
            if (mapper.updateById(requirement) != 1) throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR);
        } catch (RuntimeException | IOException failure) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) cleanup(uploaded);
            throw failure;
        }
        return view(entry);
    }
    public List<AttachmentView> list(Long id) {
        Requirement requirement = requireReadable(id);
        return stored(requirement).stream().map(GuestDemandAttachmentService::view).toList();
    }
    public void download(Long id, String key, HttpServletResponse response) throws IOException {
        Requirement requirement = requireReadable(id);
        StoredAttachment attachment = stored(requirement).stream().filter(entry -> entry.key().equals(key)).findFirst()
            .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND));
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        oss.downloadPrivate(attachment.ossId(), storageConfigKey, response);
    }
    Requirement requireReadable(Long id) {
        IpdActor actor = permission.requireInternal();
        Requirement requirement = mapper.selectOne(new LambdaQueryWrapper<Requirement>().eq(Requirement::getId, id).apply("tenant_id = {0}", tenant()));
        if (requirement == null) throw denied();
        boolean allowed = "SUPER_ADMIN".equals(actor.role()) || Objects.equals(actor.id(), requirement.getMarketPmId()) || Objects.equals(actor.id(), requirement.getRdPmId());
        if (!allowed && requirement.getProductLineId() != null) {
            var line = lines.selectOne(new LambdaQueryWrapper<org.ruoyi.ipd.domain.ProductLine>().eq(org.ruoyi.ipd.domain.ProductLine::getId, requirement.getProductLineId()).apply("tenant_id = {0}", tenant()));
            allowed = line != null && "ACTIVE".equals(line.getStatus()) && Objects.equals(actor.id(), line.getLeaderPersonId())
                && lineMembers.selectCount(new LambdaQueryWrapper<org.ruoyi.ipd.domain.ProductLineMember>()
                    .eq(org.ruoyi.ipd.domain.ProductLineMember::getProductLineId, line.getId())
                    .eq(org.ruoyi.ipd.domain.ProductLineMember::getPersonId, actor.id())
                    .eq(org.ruoyi.ipd.domain.ProductLineMember::getStatus, "ACTIVE").apply("tenant_id = {0}", tenant())) > 0;
        }
        if (!allowed) throw denied();
        return requirement;
    }
    private String tenant() {
        if (authSession == null) throw denied();
        org.ruoyi.ipd.domain.Person person = authSession.currentPerson();
        String value = person == null ? null : person.getTenantId();
        if (value == null || value.isBlank()) throw denied();
        return value;
    }
    private void cleanup(SysOssVo uploaded) {
        Long ossId = uploaded.getOssId();
        try {
            oss.cleanupUploadedObject(ossId, uploaded.getService(), uploaded.getFileName());
        } catch (RuntimeException failure) {
            log.warn("需求附件回滚清理失败，存储编号={}，类型={}", ossId, failure.getClass().getSimpleName());
        }
    }
    private static AttachmentView view(StoredAttachment file) { return new AttachmentView(file.key(), file.fileName(), file.fileSize()); }
    private static IpdBusinessException invalid() { return new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID); }
    private static IpdBusinessException denied() { return new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN); }
}
