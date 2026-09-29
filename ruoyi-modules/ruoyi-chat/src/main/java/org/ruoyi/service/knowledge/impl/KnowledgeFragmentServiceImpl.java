package org.ruoyi.service.knowledge.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.knowledge.KnowledgeFragmentBo;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.service.knowledge.IKnowledgeFragmentService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 知识片段Service业务层处理
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KnowledgeFragmentServiceImpl implements IKnowledgeFragmentService {

    private final KnowledgeFragmentMapper baseMapper;
    private final IKnowledgeInfoService knowledgeInfoService;
    private final KnowledgeAccessGate knowledgeAccessGate;
    private final IChatModelService chatModelService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final org.ruoyi.service.vector.VectorStoreService vectorStoreService;

    /**
     * 查询知识片段
     *
     * @param id 主键
     * @return 知识片段
     */
    @Override
    public KnowledgeFragmentVo queryById(Long id){
        // B 口收敛（B0 审计破坏面 B）：/{id} 按片段主键明文直读任意库片段。
        // 先取行得到其归属 kid，再过读面门（owned || share=1 可见即够，与检索口同判据）；
        // 片段无归属 kid（防御分支）与不存在的片段同样不外泄内容。
        KnowledgeFragmentVo fragment = baseMapper.selectVoById(id);
        if (fragment != null) {
            knowledgeAccessGate.checkRetrievalAccess(fragment.getKnowledgeId());
        }
        return fragment;
    }

    /**
     * 分页查询知识片段列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 知识片段分页列表
     */
    @Override
    public TableDataInfo<KnowledgeFragmentVo> queryPageList(KnowledgeFragmentBo bo, PageQuery pageQuery) {
        // B 口收敛（/system/fragment/list）：knowledgeId 为他人库时分页拉取片段明文，kid 级过读面门
        checkListAccessByKnowledgeId(bo);
        LambdaQueryWrapper<KnowledgeFragment> lqw = buildQueryWrapper(bo);
        Page<KnowledgeFragmentVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的知识片段列表
     *
     * @param bo 查询条件
     * @return 知识片段列表
     */
    @Override
    public List<KnowledgeFragmentVo> queryList(KnowledgeFragmentBo bo) {
        // B 口收敛（/system/fragment/export）：整库导出 Excel 前按 kid 过读面门
        checkListAccessByKnowledgeId(bo);
        LambdaQueryWrapper<KnowledgeFragment> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    /**
     * B 口收敛公共入口：列表/导出查询携带 knowledgeId 条件时按 kid 过读面门
     * （owned || share=1 可见即够——片段读面与检索口同判据，不另立口径）。
     * knowledgeId 为空的裸列查询面不在本轮收敛范围（登记：B2 片段列表 S2 化时统一收窄）。
     */
    private void checkListAccessByKnowledgeId(KnowledgeFragmentBo bo) {
        if (bo.getKnowledgeId() != null) {
            knowledgeAccessGate.checkRetrievalAccess(bo.getKnowledgeId());
        }
    }

    private LambdaQueryWrapper<KnowledgeFragment> buildQueryWrapper(KnowledgeFragmentBo bo) {
        LambdaQueryWrapper<KnowledgeFragment> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(KnowledgeFragment::getId);
        lqw.eq(bo.getDocId() != null, KnowledgeFragment::getDocId, bo.getDocId());
        lqw.eq(bo.getIdx() != null, KnowledgeFragment::getIdx, bo.getIdx());
        lqw.eq(StringUtils.isNotBlank(bo.getContent()), KnowledgeFragment::getContent, bo.getContent());
        return lqw;
    }

    /**
     * 新增知识片段
     *
     * @param bo 知识片段
     * @return 是否新增成功
     */
    @Override
    public Boolean insertByBo(KnowledgeFragmentBo bo) {
        KnowledgeFragment add = MapstructUtils.convert(bo, KnowledgeFragment.class);
        validEntityBeforeSave(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    /**
     * 修改知识片段
     *
     * @param bo 知识片段
     * @return 是否修改成功
     */
    @Override
    public Boolean updateByBo(KnowledgeFragmentBo bo) {
        KnowledgeFragment update = MapstructUtils.convert(bo, KnowledgeFragment.class);
        validEntityBeforeSave(update);
        boolean updated = baseMapper.updateById(update) > 0;
        if (updated && update.getKnowledgeId() != null) {
            knowledgeRetrievalService.invalidateKnowledge(String.valueOf(update.getKnowledgeId()));
        }
        return updated;
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(KnowledgeFragment entity){
        //TODO 做一些数据校验,如唯一约束
    }

    /**
     * 校验并批量删除知识片段信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if(isValid){
            //TODO 做一些业务上的校验,判断是否需要校验
        }
        // 删除 DB 片段前，同步删除向量库中对应向量
        List<KnowledgeFragment> fragments = baseMapper.selectByIds(ids);
        for (KnowledgeFragment fragment : fragments) {
            if (StringUtils.isNotBlank(fragment.getFid()) && fragment.getKnowledgeId() != null) {
                vectorStoreService.removeByFid(fragment.getFid(), String.valueOf(fragment.getKnowledgeId()));
                knowledgeRetrievalService.invalidateKnowledge(String.valueOf(fragment.getKnowledgeId()));
            }
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    /**
     * 检索测试核心实现 - 委托给统一的 KnowledgeRetrievalService。
     * S1：kid 进入检索上下文前先过检索访问门，不可见即抛业务异常。
     */
    @Override
    public List<KnowledgeRetrievalVo> retrieval(KnowledgeFragmentBo bo) {
        if (bo.getKnowledgeId() == null || StringUtils.isBlank(bo.getQuery())) {
            return new ArrayList<>();
        }
        knowledgeAccessGate.checkRetrievalAccess(bo.getKnowledgeId());

        // 1. 获取知识库及模型配置（为了获取 API Key/Host 等模型参数）
        KnowledgeInfoVo knowledgeInfoVo = knowledgeInfoService.queryById(bo.getKnowledgeId());
        if (knowledgeInfoVo == null) {
            return new ArrayList<>();
        }

        ChatModelVo chatModel = chatModelService.selectModelByName(knowledgeInfoVo.getEmbeddingModel());
        if (chatModel == null) {
            log.warn("未找到对应的向量模型配置: {}", knowledgeInfoVo.getEmbeddingModel());
            return new ArrayList<>();
        }

        // 2. 构造通用的参数对象
        QueryVectorBo queryVectorBo = new QueryVectorBo();
        queryVectorBo.setQuery(bo.getQuery());
        queryVectorBo.setKid(String.valueOf(bo.getKnowledgeId()));
        queryVectorBo.setBaseUrl(chatModel.getApiHost());
        queryVectorBo.setEmbeddingModelName(knowledgeInfoVo.getEmbeddingModel());
        queryVectorBo.setVectorModelName(knowledgeInfoVo.getVectorModel());

        // 使用前端传入的实时测试参数，若无则使用知识库默认参数
        queryVectorBo.setMaxResults(bo.getTopK() != null ? bo.getTopK() : knowledgeInfoVo.getRetrieveLimit());
        queryVectorBo.setSimilarityThreshold(bo.getThreshold() != null ? bo.getThreshold() : knowledgeInfoVo.getSimilarityThreshold());
        
        queryVectorBo.setEnableHybrid(bo.getEnableHybrid() != null ? bo.getEnableHybrid() : Objects.equals(knowledgeInfoVo.getEnableHybrid(), 1));
        queryVectorBo.setHybridAlpha(bo.getHybridAlpha() != null ? bo.getHybridAlpha() : knowledgeInfoVo.getHybridAlpha());

        queryVectorBo.setEnableRerank(bo.getEnableRerank() != null ? bo.getEnableRerank() : Objects.equals(knowledgeInfoVo.getEnableRerank(), 1));
        queryVectorBo.setRerankModelName(StringUtils.isNotBlank(bo.getRerankModel()) ? bo.getRerankModel() : knowledgeInfoVo.getRerankModel());
        queryVectorBo.setRerankTopN(bo.getTopK() != null ? bo.getTopK() : knowledgeInfoVo.getRerankTopN());
        queryVectorBo.setRerankScoreThreshold(bo.getThreshold() != null ? bo.getThreshold() : knowledgeInfoVo.getRerankScoreThreshold());

        // 3. 执行统一检索
        return knowledgeRetrievalService.retrieve(queryVectorBo);
    }
}
