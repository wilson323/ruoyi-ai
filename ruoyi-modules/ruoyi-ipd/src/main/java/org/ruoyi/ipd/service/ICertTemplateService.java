package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.CertTemplate;
import org.ruoyi.ipd.mapper.CertTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ICertTemplateService 接口（paiban-05 接口化，实现见 {@link CertTemplateService}）。
 */
public interface ICertTemplateService {

    /** 按目标市场国家代码数组解析认证清单（如 ["SA","AE"]）；未匹配国别返回空表由前端提示手工补充 */
    List<CertTemplate> resolve(String[] targetMarkets);

    /** 按国别统计（管理视图：国家 → 认证项数） */
    Map<String, Long> countByCountry();

    /** 按国别统计（管理视图：国家 → 认证项数） */
    List<CertTemplate> listAll();

    /** 按国别统计（管理视图：国家 → 认证项数） */
    CertTemplate create(CertTemplate template, Long operatorId);

    /** * 禁止直删旁路（P0-6.2 / G-02）：认证模板须走删除审核引擎。 */
    /** * */
    /** * @param id         模板 ID（仅用于错误上下文） */
    /** * @param operatorId 操作人（保留签名兼容，不执行删除） */
    /** * @throws ServiceException 始终拒绝，提示走 DeletionRequest */
    void remove(Long id, Long operatorId);

}
