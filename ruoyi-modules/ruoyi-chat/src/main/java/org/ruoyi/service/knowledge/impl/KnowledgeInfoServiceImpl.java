package org.ruoyi.service.knowledge.impl;

import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;
import org.ruoyi.enums.KnowledgeSensitivity;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.knowledge.DocumentSplitConfig;
import org.ruoyi.common.core.service.OssService;

import java.util.List;
import java.util.Map;
import java.util.Collection;

/**
 * 知识库Service业务层处理
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KnowledgeInfoServiceImpl implements IKnowledgeInfoService {

    private final KnowledgeInfoMapper baseMapper;

    private final KnowledgeAttachMapper knowledgeAttachMapper;

    private final org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper knowledgeFragmentMapper;

    private final org.ruoyi.service.vector.VectorStoreService vectorStoreService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final OssService ossService;

    /**
     * 管理面访问门（C 口收敛）：edit/remove 等 ownership 面统一过 assertManageable，
     * 判据仅 owned（share=1 公开不授予写权），superadmin 豁免。
     * 构造循环由 Gate 实现侧 @Lazy 打破（Gate 判 owned 需回调本 Service 查库）。
     */
    private final KnowledgeAccessGate knowledgeAccessGate;

    /**
     * 查询知识库
     *
     * @param id 主键
     * @return 知识库
     */
    @Override
    public KnowledgeInfoVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询知识库列表。
     * S2：userId 会话派生，客户端值一律覆盖，不信任前端传入。
     * <p>
     * 行为变化（S2 判据与 Gate 同构）：列表可见范围 =「我的库（userId 归属）+ 公开库（share=1）」，
     * 与 {@link org.ruoyi.service.knowledge.KnowledgeAccessGate} 的检索可见判据一致；
     * 他人私有库从列表中隔离属于安全收窄（此前仅按 userId 过滤时 share=1 公开库也会消失）。
     * 全量管理视图如需开放，走 admin 专用端点（B1/B2 再议），不在本判据内放行。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 知识库分页列表
     */
    @Override
    public TableDataInfo<KnowledgeInfoVo> queryPageList(KnowledgeInfoBo bo, PageQuery pageQuery) {
        bo.setUserId(LoginHelper.getUserId());
        LambdaQueryWrapper<KnowledgeInfo> lqw = buildQueryWrapper(bo);
        Page<KnowledgeInfoVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        // 批量填充文档数
        fillDocumentCount(result.getRecords());
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的知识库列表。
     * S2：userId 会话派生，客户端值一律覆盖，不信任前端传入；
     * 可见范围与 queryPageList 同构（我的 + 公开 share=1），见其 javadoc 行为说明。
     *
     * @param bo 查询条件
     * @return 知识库列表
     */
    @Override
    public List<KnowledgeInfoVo> queryList(KnowledgeInfoBo bo) {
        bo.setUserId(LoginHelper.getUserId());
        LambdaQueryWrapper<KnowledgeInfo> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<KnowledgeInfo> buildQueryWrapper(KnowledgeInfoBo bo) {
        LambdaQueryWrapper<KnowledgeInfo> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(KnowledgeInfo::getId);
        // S2 同构判据：可见范围 =「我的（userId 归属）OR 公开（share=1）」，与 KnowledgeAccessGate 检索判据一致。
        // userId 为 null 时保持原语义不挂该组条件（端点登录态下已被排除；防御性保留，避免把 null 当过滤值全拒）。
        lqw.and(bo.getUserId() != null, w -> w
            .eq(KnowledgeInfo::getUserId, bo.getUserId())
            .or()
            .eq(KnowledgeInfo::getShare, 1L));
        lqw.like(StringUtils.isNotBlank(bo.getName()), KnowledgeInfo::getName, bo.getName());
        lqw.eq(bo.getShare() != null, KnowledgeInfo::getShare, bo.getShare());
        // B1 列表通道接线：sensitivity 为权威字段（share 只是派生镜像），查询参数此前未挂
        // 谓词被完全忽略（传 PUBLIC 仍返回 INTERNAL 行）。仅显式携带（非空非空白）时
        // 精确匹配；未携带=不过滤，与同方法内其他 String 字段谓词模式一致。
        lqw.eq(StringUtils.isNotBlank(bo.getSensitivity()), KnowledgeInfo::getSensitivity, bo.getSensitivity());
        lqw.eq(StringUtils.isNotBlank(bo.getDescription()), KnowledgeInfo::getDescription, bo.getDescription());
        lqw.eq(StringUtils.isNotBlank(bo.getSeparator()), KnowledgeInfo::getSeparator, bo.getSeparator());
        lqw.eq(bo.getOverlapChar() != null, KnowledgeInfo::getOverlapChar, bo.getOverlapChar());
        lqw.eq(bo.getRetrieveLimit() != null, KnowledgeInfo::getRetrieveLimit, bo.getRetrieveLimit());
        lqw.eq(bo.getTextBlockSize() != null, KnowledgeInfo::getTextBlockSize, bo.getTextBlockSize());
        lqw.eq(StringUtils.isNotBlank(bo.getVectorModel()), KnowledgeInfo::getVectorModel, bo.getVectorModel());
        lqw.eq(StringUtils.isNotBlank(bo.getEmbeddingModel()), KnowledgeInfo::getEmbeddingModel, bo.getEmbeddingModel());
        return lqw;
    }

    /**
     * 批量填充知识库列表每一条记录的文档数（documentCount）
     */
    private void fillDocumentCount(List<KnowledgeInfoVo> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream().map(KnowledgeInfoVo::getId).toList();
        Map<Long, Integer> counts = new java.util.HashMap<>();
        for (Map<String, Object> row : knowledgeAttachMapper.countByKnowledgeIds(ids)) {
            Number kid = (Number) (row.get("knowledgeId") != null ? row.get("knowledgeId") : row.get("knowledgeid"));
            Number count = (Number) (row.get("documentCount") != null ? row.get("documentCount") : row.get("documentcount"));
            if (kid != null && count != null) counts.put(kid.longValue(), count.intValue());
        }
        records.forEach(vo -> vo.setDocumentCount(counts.getOrDefault(vo.getId(), 0)));
    }

    /**
     * 新增知识库
     *
     * @param bo 知识库
     * @return 是否新增成功
     */
    @Override
    public Boolean insertByBo(KnowledgeInfoBo bo) {
        KnowledgeInfo add = MapstructUtils.convert(bo, KnowledgeInfo.class);
        // B1 §8.1：sensitivity 权威写点 + share 派生镜像（新增路径，缺省 INTERNAL）
        applySensitivityShareMirror(add, true);
        validEntityBeforeSave(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    /**
     * 修改知识库
     *
     * @param bo 知识库
     * @return 是否修改成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(KnowledgeInfoBo bo) {
        // C 口收敛（B0 审计破坏面 C）：edit 无 ownership 校验时，任何持 system:info:edit 者可
        // UPDATE 任意库 share=1，即刻击穿 Gate 读面判据的 share 半边（提权链）。统一过管理面门。
        knowledgeAccessGate.assertManageable(bo.getId());
        KnowledgeInfo update = MapstructUtils.convert(bo, KnowledgeInfo.class);
        // B1 §8.1：显式携带 sensitivity 时派生 share；未携带时二者均不动（部分更新保既有值）
        applySensitivityShareMirror(update, false);
        validEntityBeforeSave(update);
        // B2 P1-1：显式携带 sensitivity 时取库内旧值（比对用 + 向量随动需要旧 embeddingModel，
        // 部分更新场景 Bo 可不带模型名）；未携带则无需随动（sensitivity 不进 SET 子句）。
        KnowledgeInfo before = update.getSensitivity() == null ? null : baseMapper.selectById(bo.getId());
        boolean updated = baseMapper.updateById(update) > 0;
        if (updated) {
            syncVectorPayloadSensitivity(bo.getId(), before, update);
            knowledgeRetrievalService.invalidateKnowledge(String.valueOf(bo.getId()));
        }
        return updated;
    }

    /**
     * B2 P1-1：sensitivity 变更随动已入库向量 payload 的同名键（B1 轮 Validator 登记
     * 「update 不随动=MySQL 与向量侧脱钩的隔离穿透」，最佳实践 §4 铁律一）。
     * <ul>
     *   <li>仅在显式携带 sensitivity 且与库内旧值不同（含旧值为 null 的老数据——
     *       顺带补标）时触发；同值/未携带零动作。</li>
     *   <li>失败语义 fail-noisy：向量侧随动抛错向上传播，MySQL 更新随本事务回滚
     *       （DB 侧不脱钩，与 deleteWithValidByIds 同款先例）；向量侧 PATCH 非事务资源，
     *       多批中途失败可能残留部分随动，重试收敛（幂等：同值再写无害）；TOCTOU 窗口
     *       与 P2-2 登记同款，可重试。</li>
     *   <li>Milvus/Qdrant 策略不承载 sensitivity 键（N2 登记缺口），默认实现 no-op。</li>
     * </ul>
     */
    private void syncVectorPayloadSensitivity(Long kid, KnowledgeInfo before, KnowledgeInfo update) {
        if (update.getSensitivity() == null || before == null) {
            return;
        }
        if (update.getSensitivity().equals(before.getSensitivity())) {
            return;
        }
        vectorStoreService.updatePayloadSensitivity(String.valueOf(kid), update.getSensitivity(),
            before.getEmbeddingModel());
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(KnowledgeInfo entity){
        int blockSize = entity.getTextBlockSize() == null
            ? DocumentSplitConfig.DEFAULT_BLOCK_SIZE : entity.getTextBlockSize().intValue();
        int overlap = entity.getOverlapChar() == null
            ? DocumentSplitConfig.DEFAULT_OVERLAP : entity.getOverlapChar().intValue();
        new DocumentSplitConfig(entity.getSeparator(), blockSize, overlap, "");
        // B1 §8.1 一致性断言（方案 §4）：显式写 sensitivity 时 share 必须等于派生值，
        // 防止未来新增写点绕过镜像（两键脱钩=读侧口径分裂的病根）。
        if (entity.getSensitivity() != null) {
            Long derivedShare = derivedShare(entity.getSensitivity());
            if (!derivedShare.equals(entity.getShare())) {
                throw new ServiceException("share 与 sensitivity 派生镜像不一致（预期 share="
                    + derivedShare + "），拒绝写入");
            }
        }
    }

    /**
     * B1 §8.1：sensitivity 权威写点 + share 派生只读镜像（写点仅 insertByBo/updateByBo）。
     * <ul>
     *   <li>insert：sensitivity 缺省 INTERNAL（自动规则禁产 SECRET，§8.1 规则 1）；
     *       显式值忽略大小写/空白归一后校验枚举。</li>
     *   <li>update：仅显式携带 sensitivity 时才重派生 share；未携带时置 share=null
     *       （MyBatis-Plus NOT_NULL 策略下不进 SET 子句），杜绝客户端单独改 share——
     *       share 自此不是安全边界，只是 sensitivity 的兼容镜像。</li>
     *   <li>非法 sensitivity 取值抛 {@link ServiceException}（fail-closed，防任意串进
     *       payload 过滤/关键词 JOIN 谓词）。</li>
     * </ul>
     */
    private void applySensitivityShareMirror(KnowledgeInfo entity, boolean insert) {
        if (insert && StringUtils.isBlank(entity.getSensitivity())) {
            entity.setSensitivity(KnowledgeSensitivity.INTERNAL.name());
        }
        if (StringUtils.isNotBlank(entity.getSensitivity())) {
            KnowledgeSensitivity parsed = KnowledgeSensitivity.parse(entity.getSensitivity());
            if (parsed == null) {
                throw new ServiceException("非法敏感级取值（仅 PUBLIC/INTERNAL/SECRET）: "
                    + entity.getSensitivity());
            }
            entity.setSensitivity(parsed.name());
            entity.setShare(derivedShare(parsed.name()));
        } else {
            entity.setShare(null);
        }
    }

    private static Long derivedShare(String sensitivity) {
        return KnowledgeSensitivity.PUBLIC.name().equals(sensitivity) ? 1L : 0L;
    }

    /**
     * 校验并批量删除知识库信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if(isValid){
            //TODO 做一些业务上的校验,判断是否需要校验
        }
        // C 口收敛：删除为不可逆破坏面（连带向量库 removeById + OSS 物理删），必须先全量过
        // 管理面门再动手——循环内中途拒绝虽可回滚 DB，但向量库/OSS 清理无事务保护，
        // 会留下「DB 已回滚、外部存储已删一半」的脏状态。
        ids.forEach(knowledgeAccessGate::assertManageable);
        for (Long kid : ids) {
            KnowledgeInfo info = baseMapper.selectById(kid);
            // 1. 删除向量库中该知识库的所有向量（按文档逐个清理，三种向量库行为一致）
            List<org.ruoyi.domain.entity.knowledge.KnowledgeAttach> attaches = knowledgeAttachMapper.selectList(
                Wrappers.lambdaQuery(org.ruoyi.domain.entity.knowledge.KnowledgeAttach.class)
                    .eq(org.ruoyi.domain.entity.knowledge.KnowledgeAttach::getKnowledgeId, kid));
            vectorStoreService.removeById(String.valueOf(kid), info == null ? null : info.getVectorModel());
            List<Long> ossIds = attaches.stream()
                    .map(org.ruoyi.domain.entity.knowledge.KnowledgeAttach::getOssId)
                    .filter(java.util.Objects::nonNull).toList();
            if (!ossIds.isEmpty()) {
                for (Long ossId : ossIds) {
                    ossService.deleteFile(ossId);
                }
            }
            // 2. 删除该知识库下的附件与片段记录
            knowledgeAttachMapper.delete(Wrappers.lambdaQuery(org.ruoyi.domain.entity.knowledge.KnowledgeAttach.class)
                .eq(org.ruoyi.domain.entity.knowledge.KnowledgeAttach::getKnowledgeId, kid));
            knowledgeFragmentMapper.delete(Wrappers.lambdaQuery(org.ruoyi.domain.entity.knowledge.KnowledgeFragment.class)
                .eq(org.ruoyi.domain.entity.knowledge.KnowledgeFragment::getKnowledgeId, kid));
            knowledgeRetrievalService.invalidateKnowledge(String.valueOf(kid));
        }
        return baseMapper.deleteByIds(ids) > 0;
    }
}
