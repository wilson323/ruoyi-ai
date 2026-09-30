package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gate 评审材料上传。只产生服务端 ossId，供 submit 的两个必填位使用。
 */
@Service
@RequiredArgsConstructor
public class GateMaterialUploadService {

    private final GateMapper gateMapper;
    private final ISysOssService ossService;

    /**
     * 上传一份 Gate 材料并返回 ossId。
     *
     * @param gateId 评审编号
     * @param file 文件
     * @return ossId 与文件名
     */
    public Map<String, String> upload(Long gateId, MultipartFile file) {
        Gate gate = gateMapper.selectById(gateId);
        if (gate == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "Gate 不存在");
        }
        if (file == null || file.isEmpty()) {
            throw new ServiceException("上传文件不能为空");
        }
        SysOssVo uploaded = ossService.upload(file);
        if (uploaded == null || uploaded.getOssId() == null) {
            throw new ServiceException("对象存储上传失败（未返回 ossId）");
        }
        Map<String, String> view = new LinkedHashMap<>();
        view.put("ossId", String.valueOf(uploaded.getOssId()));
        view.put("fileName", file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        view.put("gateId", String.valueOf(gateId));
        return view;
    }
}
