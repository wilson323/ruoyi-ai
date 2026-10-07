package org.ruoyi.service.knowledge.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.enums.KnowledgeAttachStatus;
import org.ruoyi.common.core.domain.dto.OssDTO;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.knowledge.KnowledgeAttachBo;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoUploadBo;
import org.ruoyi.domain.bo.vector.StoreEmbeddingBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;
import org.ruoyi.domain.vo.knowledge.DocFragmentCountVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeAttachVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeReparseVo;
import org.ruoyi.service.knowledge.KnowledgeEmbedEndpoint;
import org.ruoyi.factory.ResourceLoaderFactory;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.service.knowledge.IKnowledgeAttachService;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.knowledge.ResourceLoader;
import org.ruoyi.service.knowledge.DocumentSplitConfig;
import org.ruoyi.service.vector.VectorStoreService;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库附件Service业务层处理
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KnowledgeAttachServiceImpl implements IKnowledgeAttachService, ApplicationRunner {

    private final KnowledgeAttachMapper baseMapper;
    private final IKnowledgeInfoService knowledgeInfoService;
    private final KnowledgeFragmentMapper knowledgeFragmentMapper;
    private final IChatModelService chatModelService;
    private final ResourceLoaderFactory resourceLoaderFactory;
    private final VectorStoreService vectorStoreService;
    private final OssService ossService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    /**
     * 管理面访问门（D 口收敛）：upload/reparse 向库注入文档属 ownership 面，
     * 统一过 assertManageable（仅 owned，share=1 公开不授予写权），superadmin 豁免。
     */
    private final KnowledgeAccessGate knowledgeAccessGate;

    /** 只在本次显式打开时，给已入库且向量为空的片段补向量。默认关闭。 */
    @Value("${ipd.knowledge.fill-empty-embeddings:false}")
    private boolean fillEmptyEmbeddings;

    /**
     * 服务起来之后再补向量，避免挡住端口监听。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!fillEmptyEmbeddings) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                fillEmptyFragmentVectors();
            } catch (RuntimeException ex) {
                log.error("[knowledge-embed] 补向量中断: {}", ex.getMessage());
            }
        }, "knowledge-empty-embed-fill");
        worker.setDaemon(false);
        worker.start();
    }

    /**
     * 用与文档嵌入相同的内置模型，给嵌入模型名为空的已有片段补向量。
     * 不改正文，不清来源备注，不改附件状态。
     *
     * @return 成功回写的片段数
     */
    public int fillEmptyFragmentVectors() {
        return TenantHelper.ignore(() -> KnowledgeEmbedEndpoint.fillStoredVectors(
            knowledgeFragmentMapper, knowledgeInfoService, vectorStoreService));
    }

    @Override
    public KnowledgeAttachVo queryById(Long id) {
        return baseMapper.selectVoById(id);
    }

    @Override
    public TableDataInfo<KnowledgeAttachVo> queryPageList(KnowledgeAttachBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<KnowledgeAttach> lqw = buildQueryWrapper(bo);
        Page<KnowledgeAttachVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        fillFragmentCount(result.getRecords());
        return TableDataInfo.build(result);
    }

    @Override
    public List<KnowledgeAttachVo> queryList(KnowledgeAttachBo bo) {
        LambdaQueryWrapper<KnowledgeAttach> lqw = buildQueryWrapper(bo);
        List<KnowledgeAttachVo> list = baseMapper.selectVoList(lqw);
        fillFragmentCount(list);
        return list;
    }

    private void fillFragmentCount(List<KnowledgeAttachVo> records) {
        if (records == null || records.isEmpty()) return;
        List<String> docIds = records.stream()
            .map(KnowledgeAttachVo::getDocId)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .collect(Collectors.toList());
        if (docIds.isEmpty()) return;
        List<DocFragmentCountVo> countList = knowledgeFragmentMapper.selectFragmentCountByDocIds(docIds);
        Map<String, Integer> countMap = countList.stream()
            .collect(Collectors.toMap(DocFragmentCountVo::getDocId, DocFragmentCountVo::getFragmentCount, (k1, k2) -> k1));
        for (KnowledgeAttachVo vo : records) {
            vo.setFragmentCount(countMap.getOrDefault(vo.getDocId(), 0));
        }
    }

    private LambdaQueryWrapper<KnowledgeAttach> buildQueryWrapper(KnowledgeAttachBo bo) {
        LambdaQueryWrapper<KnowledgeAttach> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(KnowledgeAttach::getId);
        lqw.eq(bo.getKnowledgeId() != null, KnowledgeAttach::getKnowledgeId, bo.getKnowledgeId());
        lqw.like(StringUtils.isNotBlank(bo.getName()), KnowledgeAttach::getName, bo.getName());
        lqw.eq(StringUtils.isNotBlank(bo.getType()), KnowledgeAttach::getType, bo.getType());
        lqw.eq(bo.getOssId() != null, KnowledgeAttach::getOssId, bo.getOssId());
        return lqw;
    }

    @Override
    public Boolean insertByBo(KnowledgeAttachBo bo) {
        KnowledgeAttach add = MapstructUtils.convert(bo, KnowledgeAttach.class);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(KnowledgeAttachBo bo) {
        KnowledgeAttach update = MapstructUtils.convert(bo, KnowledgeAttach.class);
        return baseMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        // 删除附件前，同步清理其片段记录与向量库中的向量
        List<KnowledgeAttach> attaches = baseMapper.selectByIds(ids);
        for (KnowledgeAttach attach : attaches) {
            String docId = attach.getDocId();
            String kid = String.valueOf(attach.getKnowledgeId());
            vectorStoreService.removeByDocId(docId, kid);
            knowledgeFragmentMapper.delete(
                Wrappers.<KnowledgeFragment>lambdaQuery().eq(KnowledgeFragment::getDocId, docId));
            if (attach.getOssId() != null) {
                ossService.deleteFile(attach.getOssId());
            }
            knowledgeRetrievalService.invalidateKnowledge(kid);
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public void upload(KnowledgeInfoUploadBo bo) {
        // D 口收敛（B0 审计破坏面 D）：bo.getKnowledgeId() 客户端直传即可向他人库注入文档
        //（经 parse 进入向量库与 RAG 检索面）。管理面门置于一切副作用之前——
        // 拒绝时不读文件流、不上传 OSS、不落 attach 表（fail-fast）。
        knowledgeAccessGate.assertManageable(bo.getKnowledgeId());
        MultipartFile file = bo.getFile();
        final String fileHash;
        try (InputStream input = file.getInputStream()) {
            fileHash = DigestUtil.sha256Hex(input);
        } catch (Exception e) {
            throw new ServiceException("计算文件摘要失败", e);
        }
        boolean duplicate = baseMapper.exists(Wrappers.<KnowledgeAttach>lambdaQuery()
            .eq(KnowledgeAttach::getKnowledgeId, bo.getKnowledgeId())
            .eq(KnowledgeAttach::getFileHash, fileHash));
        if (duplicate) {
            throw new ServiceException("该文件已上传，请勿重复提交");
        }
        OssDTO ossDTO = ossService.uploadFile(file);

        KnowledgeAttach knowledgeAttach = new KnowledgeAttach();
        knowledgeAttach.setKnowledgeId(bo.getKnowledgeId());
        knowledgeAttach.setOssId(ossDTO.getOssId());
        knowledgeAttach.setDocId(RandomUtil.randomString(10));
        knowledgeAttach.setFileHash(fileHash);
        knowledgeAttach.setName(ossDTO.getOriginalName());
        knowledgeAttach.setType(ossDTO.getFileSuffix());
        knowledgeAttach.setStatus(KnowledgeAttachStatus.WAITING.getCode()); // 待解析

        baseMapper.insert(knowledgeAttach);

        if (Boolean.TRUE.equals(bo.getAutoParse())) {
            // 通过 SpringUtils 获取代理对象，确保 @Async 生效
            SpringUtils.getBean(IKnowledgeAttachService.class).parse(knowledgeAttach.getId());
        }
    }

    @Async("knowledgeParseExecutor")
    @Override
    public void parse(Long id) {
        KnowledgeAttach attach = baseMapper.selectById(id);
        if (attach == null || KnowledgeAttachStatus.PARSING.getCode().equals(attach.getStatus())) {
            return;
        }

        int claimed = baseMapper.update(null, Wrappers.<KnowledgeAttach>lambdaUpdate()
            .set(KnowledgeAttach::getStatus, KnowledgeAttachStatus.PARSING.getCode())
            .eq(KnowledgeAttach::getId, id)
            .ne(KnowledgeAttach::getStatus, KnowledgeAttachStatus.PARSING.getCode()));
        if (claimed == 0) return;

        try {
            attach.setStatus(KnowledgeAttachStatus.PARSING.getCode()); // 解析中
            baseMapper.updateById(attach);

            log.info("开始解析知识库文档... id: {}, docId: {}", id, attach.getDocId());

            Long knowledgeId = attach.getKnowledgeId();
            String docId = attach.getDocId();
            KnowledgeInfoVo knowledgeInfoVo = knowledgeInfoService.queryById(knowledgeId);
            if (knowledgeInfoVo == null) {
                throw new ServiceException("知识库不存在: " + knowledgeId);
            }
            int blockSize = knowledgeInfoVo.getTextBlockSize() == null
                ? DocumentSplitConfig.DEFAULT_BLOCK_SIZE : knowledgeInfoVo.getTextBlockSize().intValue();
            int overlap = knowledgeInfoVo.getOverlapChar() == null
                ? DocumentSplitConfig.DEFAULT_OVERLAP : knowledgeInfoVo.getOverlapChar().intValue();
            KnowledgeEmbedEndpoint.Choice embedChoice = KnowledgeEmbedEndpoint.resolve(
                knowledgeInfoVo.getEmbeddingModel());
            DocumentSplitConfig splitConfig = new DocumentSplitConfig(
                knowledgeInfoVo.getSeparator(), blockSize, overlap, attach.getType());

            // 获取文件信息并下载
            List<OssDTO> ossDTOs = ossService.selectByIds(String.valueOf(attach.getOssId()));
            if (ossDTOs == null || ossDTOs.isEmpty()) {
                throw new RuntimeException("未找到对应的 OSS 文件信息");
            }
            OssDTO ossDTO = ossDTOs.get(0);
            String content;
            ResourceLoader resourceLoader = resourceLoaderFactory.getLoaderByFileType(attach.getType());
            try (InputStream inputStream = new URL(ossDTO.getUrl()).openStream()) {
                content = resourceLoader.getContent(inputStream);
            }
            List<String> chunkList = resourceLoader.getChunkList(content, splitConfig);

            if (CollUtil.isEmpty(chunkList)) {
                throw new RuntimeException("文档分片结果为空，请检查文档内容或分片器是否支持该文件类型");
            }

            // 重新解析前先清理旧的向量数据，避免向量重复累积
            List<String> fids = new ArrayList<>();
            List<KnowledgeFragment> knowledgeFragmentList = new ArrayList<>();
            Date embeddedAt = new Date();
            for (int i = 0; i < chunkList.size(); i++) {
                String fid = RandomUtil.randomString(10);
                fids.add(fid);
                KnowledgeFragment knowledgeFragment = new KnowledgeFragment();
                knowledgeFragment.setKnowledgeId(knowledgeId);
                knowledgeFragment.setDocId(docId);
                knowledgeFragment.setFid(fid);
                knowledgeFragment.setIdx(i);
                knowledgeFragment.setContent(chunkList.get(i));
                knowledgeFragment.setCreateTime(new Date());
                // B1 §2.4 三元组：模型名与嵌入时间在分片时快照；维度取嵌入实测值，
                // 待 storeEmbeddings 回填后统一补（见下方 actualDim 赋值处）。
                knowledgeFragment.setEmbeddingModel(embedChoice.modelName());
                knowledgeFragment.setEmbeddedAt(embeddedAt);
                knowledgeFragmentList.add(knowledgeFragment);
            }
            ChatModelVo chatModelVo = null;
            if (!embedChoice.builtin()) {
                chatModelVo = chatModelService.selectModelByName(embedChoice.modelName());
                if (chatModelVo == null) {
                    throw new ServiceException("未找到对应的向量模型配置: " + embedChoice.modelName());
                }
            }

            StoreEmbeddingBo storeEmbeddingBo = new StoreEmbeddingBo();
            storeEmbeddingBo.setKid(String.valueOf(knowledgeId));
            storeEmbeddingBo.setDocId(docId);
            storeEmbeddingBo.setFids(fids);
            storeEmbeddingBo.setChunkList(chunkList);
            storeEmbeddingBo.setVectorStoreName(knowledgeInfoVo.getVectorModel());
            storeEmbeddingBo.setEmbeddingModelName(embedChoice.modelName());
            storeEmbeddingBo.setBaseUrl(embedChoice.builtin() ? embedChoice.baseUrl() : chatModelVo.getApiHost());
            // B1 四刀之二：payload 归属冗余值随片段同批写入向量侧（WeaviatePayloadKeys 驼峰键）。
            // MySQL 与向量侧同一批事实（最佳实践 §4 铁律一）；owner_person_id 由现有
            // user_id 语义承担（最佳实践 §3 组一）。库级未配置的归属维为 null → 不写键（空态降级）。
            storeEmbeddingBo.setScopeType(knowledgeInfoVo.getScopeType());
            storeEmbeddingBo.setGroupId(knowledgeInfoVo.getGroupId());
            storeEmbeddingBo.setProjectId(knowledgeInfoVo.getProjectId());
            storeEmbeddingBo.setOwnerPersonId(knowledgeInfoVo.getUserId());
            storeEmbeddingBo.setOwnerAgentId(knowledgeInfoVo.getOwnerAgentId());
            storeEmbeddingBo.setSensitivity(knowledgeInfoVo.getSensitivity());
            // 2026-10-07 修（根因修复 1/4）：向量库失败**不再阻断 MySQL 切片入库**。
            //
            // 原行为：向量库写失败 → 向上抛 → 整个方法退出 → 下面的
            // knowledgeFragmentMapper.insertBatch(...) **永远执行不到**。
            // 而文本检索（ProjectKnowledgeFragmentTextSearch）只查 MySQL 的
            // knowledge_fragment，**完全不依赖向量库** —— 却��向量库失败被一起写不进去。
            //
            // 后果（2026-10-07 实测）：向量库 28080 未启动时，
            // knowledge_info / knowledge_fragment 双表恒为 0 行，
            // 上传接口返回 200、用户以为成功、智能体检索永远返回「未取得」。
            //
            // 改为：向量库失败只做补偿清理 + 告警，**继续往下写 MySQL**。
            // 降级语义明确：向量检索不可用，但关键词检索立即可用。
            boolean vectorStoreOk = true;
            try {
                // 写入新向量前，先按 docId 清理该文档的旧向量：
                // 历史数据的片段 fid 为迁移脚本回填的 MD5 值，与向量库中实际存储的 fid 不一致，
                // 按 fid 删除无法命中旧向量，会导致重复向量累积；按 docId 清理对三种向量库均一致有效。
                vectorStoreService.removeByDocId(docId, String.valueOf(knowledgeId));
                vectorStoreService.storeEmbeddings(storeEmbeddingBo);
            } catch (Exception vectorError) {
                vectorStoreOk = false;
                for (String newFid : fids) {
                    try {
                        vectorStoreService.removeByFid(newFid, String.valueOf(knowledgeId));
                    } catch (Exception cleanupError) {
                        log.error("补偿删除新向量失败, kid={}, fid={}", knowledgeId, newFid, cleanupError);
                    }
                }
                // 不再 rethrow：MySQL 切片必须落库，否则不依赖向量库的关键词检索也一起废掉。
                log.warn("[KB-DEGRADED] 向量库不可用，本次仅写入 MySQL 切片（关键词检索可用，"
                                + "向量/语义检索不可用）。kid={}, docId={}, 原因={}",
                        knowledgeId, docId, vectorError.getMessage(), vectorError);
            }

            // 三元组补维：storeEmbeddings 已以 Embedding#dimension() 实测值回写 Bo
            // （「实际用了什么」权威于模型配置值，最佳实践 §3 组三）。
            Integer actualDim = storeEmbeddingBo.getEmbeddingDim();
            if (actualDim != null) {
                for (KnowledgeFragment fragment : knowledgeFragmentList) {
                    fragment.setEmbeddingDim(actualDim);
                }
            }
            knowledgeFragmentMapper.delete(Wrappers.<KnowledgeFragment>lambdaQuery().eq(KnowledgeFragment::getDocId, docId));
            knowledgeFragmentMapper.insertBatch(knowledgeFragmentList);
            knowledgeRetrievalService.invalidateKnowledge(String.valueOf(knowledgeId));

            // 2026-10-07 修（根因修复 1/4 续）：降级时**不能报「已完成」**——
            // 那等于换一种方式继续骗用户（原文说「已成功但其实没存进去」，
            // 降级后是「存进去了但向量检索不可用」，两者对用户意义完全不同）。
            // 这里把降级事实写进 remark，状态仍为 COMPLETED（切片确实入库了），
            // 但任何只看状态的人都能在 remark 里看到「向量检索不可用」。
            if (vectorStoreOk) {
                attach.setStatus(KnowledgeAttachStatus.COMPLETED.getCode()); // 已完成
                log.info("知识库文档解析、向量化并入库成功！id: {}", id);
            } else {
                attach.setStatus(KnowledgeAttachStatus.COMPLETED.getCode()); // 已完成（降级）
                attach.setRemark("【已降级】切片已入库，关键词检索可用；但向量库不可用，语义检索暂不可用。");
                log.warn("[KB-DEGRADED] 附件以降级状态完成：id={}, kid={}", id, knowledgeId);
            }
            baseMapper.updateById(attach);
        } catch (Exception e) {
            log.error("解析文档失败！id: {}, error: {}", id, e.getMessage(), e);
            attach.setStatus(KnowledgeAttachStatus.FAILED.getCode()); // 失败
            attach.setRemark(KnowledgeEmbedEndpoint.remarkAfterFailure(attach.getRemark(), e.getMessage()));
            baseMapper.updateById(attach);
        }
    }

    @Override
    public KnowledgeReparseVo reparseKnowledge(Long knowledgeId) {
        // D 口收敛：整库 reparse = 清他人库旧向量 + 重写内容，破坏性等同写面，先过管理面门
        //（拒绝时不触 attach 表查询、不触发任何 parse 重解析）。
        knowledgeAccessGate.assertManageable(knowledgeId);
        List<KnowledgeAttach> attachments = baseMapper.selectList(
            Wrappers.<KnowledgeAttach>lambdaQuery().eq(KnowledgeAttach::getKnowledgeId, knowledgeId));
        int submitted = 0;
        int skipped = 0;
        IKnowledgeAttachService proxy = SpringUtils.getBean(IKnowledgeAttachService.class);
        for (KnowledgeAttach attachment : attachments) {
            if (KnowledgeAttachStatus.PARSING.getCode().equals(attachment.getStatus())) {
                skipped++;
            } else {
                proxy.parse(attachment.getId());
                submitted++;
            }
        }
        return new KnowledgeReparseVo(submitted, skipped, attachments.size());
    }
}
