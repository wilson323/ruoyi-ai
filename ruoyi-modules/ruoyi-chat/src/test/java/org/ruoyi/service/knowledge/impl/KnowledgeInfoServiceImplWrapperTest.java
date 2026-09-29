package org.ruoyi.service.knowledge.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.vector.VectorStoreService;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * S2 同构判据直测：buildQueryWrapper 的可见范围 =「我的（user_id = ?）OR 公开（share = 1）」，
 * 与 {@link UserIdShareKnowledgeAccessGate} 的检索可见判据同构（他人私有库从列表隔离，公开库不再消失）。
 * <p>
 * 本模块无 service 层 wrapper 测试先例：不引入 H2 / MyBatis 集成测试设施（搭造成本高），
 * 与 KnowledgeRetrievalCacheIdentityTest 同做法采用反射直测私有方法；仅以 TableInfoHelper
 * 初始化实体的 lambda 列缓存使 wrapper 可产出 SQL 段，断言 SQL 段结构与绑定参数值。
 * 纯 mock 用例，未覆盖 DDL 合法性与真实 SQL 执行（后续 HTTP 验收补）。
 */
@Tag("dev")
class KnowledgeInfoServiceImplWrapperTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), KnowledgeInfo.class);
    }

    private static KnowledgeInfoServiceImpl newService() {
        return new KnowledgeInfoServiceImpl(
            mock(KnowledgeInfoMapper.class),
            mock(KnowledgeAttachMapper.class),
            mock(KnowledgeFragmentMapper.class),
            mock(VectorStoreService.class),
            mock(KnowledgeRetrievalService.class),
            mock(OssService.class),
            mock(org.ruoyi.service.knowledge.KnowledgeAccessGate.class));
    }

    private static LambdaQueryWrapper<KnowledgeInfo> buildWrapper(KnowledgeInfoBo bo) throws Exception {
        Method method = KnowledgeInfoServiceImpl.class.getDeclaredMethod("buildQueryWrapper", KnowledgeInfoBo.class);
        method.setAccessible(true);
        return (LambdaQueryWrapper<KnowledgeInfo>) method.invoke(newService(), bo);
    }

    @Test
    void visibleScopeIsOwnedOrSharedIsomorphicWithGate() throws Exception {
        KnowledgeInfoBo bo = new KnowledgeInfoBo();
        bo.setUserId(100L);
        LambdaQueryWrapper<KnowledgeInfo> lqw = buildWrapper(bo);
        String sql = lqw.getSqlSegment();
        int pOpen = sql.indexOf('(');
        int pUser = sql.indexOf("user_id");
        int pOr = sql.indexOf(" OR ");
        int pShare = sql.indexOf("share");
        int pClose = sql.indexOf(')');
        assertTrue(pOpen >= 0 && pUser > pOpen, "归属条件须在括号组内，实际=" + sql);
        assertTrue(pOr > pUser, "归属与公开须为 OR 关系而非 AND，实际=" + sql);
        assertTrue(pShare > pOr, "share=1 公开条件须在 OR 右侧，实际=" + sql);
        assertTrue(pClose > pShare, "OR 组须整体加括号（不与其他条件错位结合），实际=" + sql);
        Map<String, Object> params = lqw.getParamNameValuePairs();
        assertTrue(params.containsValue(100L), "组内应绑定会话派生 userId，实际=" + params);
        assertTrue(params.containsValue(1L), "组内应绑定公开常量 share=1，实际=" + params);
    }

    @Test
    void nullUserIdSkipsVisibilityGroup() throws Exception {
        // 防御语义保留：userId 为 null 时不挂可见性组（端点登录态已排除该场景，勿把 null 当过滤值全拒）
        KnowledgeInfoBo bo = new KnowledgeInfoBo();
        bo.setUserId(null);
        LambdaQueryWrapper<KnowledgeInfo> lqw = buildWrapper(bo);
        String sql = lqw.getSqlSegment();
        assertFalse(sql.contains("user_id"), "userId=null 不应挂归属条件，实际=" + sql);
        assertFalse(sql.contains("share"), "userId=null 不应挂 OR 公开条件，实际=" + sql);
    }

    @Test
    void explicitShareFilterStillAppliesAlongsideVisibilityGroup() throws Exception {
        // 既有行为不回归：显式传 share 过滤值时仍生成独立 eq 条件（与 OR 组内常量 1 并存）
        KnowledgeInfoBo bo = new KnowledgeInfoBo();
        bo.setUserId(100L);
        bo.setShare(1L);
        LambdaQueryWrapper<KnowledgeInfo> lqw = buildWrapper(bo);
        // MP wrapper 参数为惰性求值：须先渲染 SQL 段，paramNameValuePairs 才填充（与上一用例同序）
        String sql = lqw.getSqlSegment();
        Map<String, Object> params = lqw.getParamNameValuePairs();
        assertTrue(sql.contains("user_id") && sql.contains("share"), "实际=" + sql);
        assertTrue(params.containsValue(100L));
        // 1L 出现两次：OR 组内公开常量 + 显式 share 过滤值
        assertTrue(params.values().stream().filter(v -> Long.valueOf(1L).equals(v)).count() == 2,
            "share=1 应同时出现在 OR 组常量与独立过滤条件，实际=" + params);
    }
}
