package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** IPD 副驾的同租户、全局公开知识关键词检索。租户必须来自 IpdCopilotAccess。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpdPublicKnowledgeService {

    private static final String IPD_DEFAULT_TENANT = "000000";
    private static final long KNOWLEDGE_DEFAULT_TENANT = 0L;
    private static final int MAX_KNOWLEDGE_IDS = 20;
    private static final int MAX_QUERY_CHARS = 500;
    private static final int MAX_RESULTS = 4;
    private static final int MAX_FRAGMENT_CHARS = 500;
    private static final int MAX_CONTEXT_CHARS = 1_800;
    private static final Pattern DECIMAL_ID = Pattern.compile("[1-9][0-9]{0,18}");

    private final KnowledgeFragmentMapper fragmentMapper;

    /**
     * knowledgeIds=null 检索全部同租户 GLOBAL/PUBLIC 库；空集合表示用户明确不选库。
     * 非空 ID 只缩小检索范围，公开性和租户每次都由 Mapper 同一 SQL 重新校验。
     * 现有知识表租户列为 BIGINT，IPD Person 是字符串；只接受已确认的单企业映射。
     */
    public RetrievalContext retrieve(String trustedPersonTenantId, List<String> knowledgeIds, String query) {
        List<Long> selectedIds = normalizeIds(knowledgeIds);
        if (selectedIds != null && selectedIds.isEmpty()) {
            return RetrievalContext.EMPTY;
        }
        if (!IPD_DEFAULT_TENANT.equals(trustedPersonTenantId)) {
            // 尚无其他 Person 租户到知识表 BIGINT tenant_id 的权威映射，不作 SQL 类型强转。
            if (selectedIds != null) {
                throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "公共知识库租户映射未配置");
            }
            return RetrievalContext.EMPTY;
        }
        if (query == null || query.isBlank()) {
            return RetrievalContext.EMPTY;
        }
        String boundedQuery = query.strip();
        if (boundedQuery.length() > MAX_QUERY_CHARS) {
            boundedQuery = boundedQuery.substring(0, MAX_QUERY_CHARS);
        }

        List<KnowledgeFragmentVo> matches;
        try {
            matches = fragmentMapper.searchIpdPublic(KNOWLEDGE_DEFAULT_TENANT, selectedIds,
                boundedQuery, MAX_RESULTS);
        } catch (RuntimeException e) {
            log.warn("公共知识检索失败，按无知识处理: errorType={}", e.getClass().getSimpleName());
            if (selectedIds != null) {
                throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR, "所选公共知识库暂不可用");
            }
            return RetrievalContext.EMPTY;
        }
        if (matches == null || matches.isEmpty()) {
            return RetrievalContext.EMPTY;
        }

        StringBuilder block = new StringBuilder();
        LinkedHashSet<String> sourceRefs = new LinkedHashSet<>();
        int count = 0;
        for (KnowledgeFragmentVo match : matches) {
            if (count >= MAX_RESULTS || match == null || match.getKnowledgeId() == null
                || match.getContent() == null || match.getContent().isBlank()) {
                continue;
            }
            String content = match.getContent().strip();
            if (content.length() > MAX_FRAGMENT_CHARS) {
                content = content.substring(0, MAX_FRAGMENT_CHARS);
            }
            String piece = "【公共知识片段 " + (count + 1) + "｜知识库 "
                + match.getKnowledgeId() + "】\n" + content + "\n";
            if (block.length() + piece.length() > MAX_CONTEXT_CHARS) {
                break;
            }
            block.append(piece);
            sourceRefs.add("knowledge.public:" + match.getKnowledgeId());
            count++;
        }
        return count == 0 ? RetrievalContext.EMPTY
            : new RetrievalContext(block.toString(), new ArrayList<>(sourceRefs));
    }

    private static List<Long> normalizeIds(List<String> ids) {
        if (ids == null) {
            return null;
        }
        if (ids.size() > MAX_KNOWLEDGE_IDS) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "最多选择 20 个知识库");
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (String raw : ids) {
            if (raw == null || !DECIMAL_ID.matcher(raw).matches()) {
                throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "知识库 ID 无效");
            }
            try {
                unique.add(Long.parseLong(raw));
            } catch (NumberFormatException e) {
                throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "知识库 ID 超出范围");
            }
        }
        return new ArrayList<>(unique);
    }

    public record RetrievalContext(String block, List<String> sourceRefs) {
        public static final RetrievalContext EMPTY = new RetrievalContext("", List.of());

        public boolean hasContent() {
            return block != null && !block.isBlank();
        }
    }
}
