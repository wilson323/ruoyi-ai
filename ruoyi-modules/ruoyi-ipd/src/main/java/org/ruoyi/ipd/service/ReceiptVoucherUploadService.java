package org.ruoyi.ipd.service;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 回款凭证附件上传（AC-INC-16c）。
 *
 * <p>文件由服务端写入对象存储并计算 SHA-256。调用方只接收 voucherUrl 与 voucherHash，
 * 不能自行指定对象编号。地址长度受台账字段 500 字符约束。
 */
@Service
@RequiredArgsConstructor
public class ReceiptVoucherUploadService {

    static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED = Set.of("pdf", "jpg", "jpeg", "png", "webp");

    private final ProjectMapper projectMapper;
    private final ISysOssService ossService;

    /**
     * 上传一份回款凭证。
     *
     * @param projectId 未删除的项目编号
     * @param file 凭证文件，仅 pdf/jpg/jpeg/png/webp，单份不超过 20MB
     * @return voucherUrl、voucherHash、fileName
     */
    public Map<String, String> upload(Long projectId, MultipartFile file) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || "1".equals(project.getDelFlag())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不存在: " + projectId);
        }
        if (file == null || file.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "上传文件不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IpdBusinessException(ApiV1ErrorCode.ATTACHMENT_TOO_LARGE,
                "凭证超过单文件 20MB 上限: " + file.getSize() + " bytes");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank() || !ALLOWED.contains(extensionOf(fileName))) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "凭证仅支持 pdf/jpg/jpeg/png/webp");
        }
        SysOssVo uploaded = ossService.upload(file);
        if (uploaded == null || uploaded.getOssId() == null) {
            throw new ServiceException("对象存储上传失败（未返回 ossId）");
        }
        SysOssVo stored = ossService.getById(uploaded.getOssId());
        if (stored == null || stored.getUrl() == null || stored.getUrl().isBlank()) {
            throw new ServiceException("OSS 对象登记不存在，拒绝返回凭证地址: ossId=" + uploaded.getOssId());
        }
        if (stored.getUrl().length() > 500) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "凭证地址超过 500 字符，无法写入台账");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new ServiceException("读取上传文件失败: " + ex.getMessage());
        }
        Map<String, String> view = new LinkedHashMap<>();
        view.put("voucherUrl", stored.getUrl());
        view.put("voucherHash", DigestUtil.sha256Hex(bytes));
        view.put("fileName", fileName);
        return view;
    }

    /** 取小写扩展名；无扩展名时返回空串，交由白名单拒绝。 */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
