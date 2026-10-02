package org.ruoyi.service.rerank;

import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.domain.bo.rerank.RerankRequest;
import org.ruoyi.domain.bo.rerank.RerankResult;

import java.util.List;

/**
 * 重排序模型服务接口
 * 保持业务重排序合同，与模型 SDK 解耦
 * 参考设计模式：BaseEmbedModelService
 *
 * @author Yzm
 * @date 2026-04-19
 */
public interface RerankModelService  {

    /**
     * 根据配置信息配置重排序模型
     *
     * @param config 包含模型配置信息的 ChatModelVo 对象
     */
    void configure(ChatModelVo config);

    /**
     * 执行重排序（批量文档）
     * 这是业务层使用的便捷方法
     *
     * @param rerankRequest 重排序请求，包含查询文本和候选文档列表
     * @return 重排序结果，包含排序后的文档和相关性分数
     */
    RerankResult rerank(RerankRequest rerankRequest);

}
