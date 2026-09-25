package org.ruoyi.ipd.service;

import jakarta.servlet.http.HttpServletResponse;
import org.ruoyi.ipd.domain.Deliverable;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * P1-4.2（卡 fde68b8c）真实附件上传下载与动作归属鉴权服务接口
 * （paiban-05 接口化约定，实现见 {@link DeliverableService}，与 IStageActionService 同形态）。
 */
public interface IDeliverableService {

    /**
     * 服务端真实上传并登记交付物（对象存在才登记；100MB/格式策略；上传者/大小/hash 入库；失败孤儿补偿）。
     *
     * @param actionId 阶段动作实例 ID
     * @param file     上传文件（服务端落 OSS，客户端不可指定 ossId）
     * @param actor    服务端会话身份
     * @return 登记的交付物
     */
    Deliverable upload(Long actionId, MultipartFile file, IpdActor actor);

    /**
     * 下载交付物附件（下载校验项目归属：在职成员或 SUPER_ADMIN，fail-closed）。
     *
     * @param deliverableId 交付物 ID
     * @param actor         服务端会话身份
     * @param response      文件流响应
     */
    void download(Long deliverableId, IpdActor actor, HttpServletResponse response) throws IOException;
}
