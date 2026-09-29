package org.ruoyi.service.knowledge.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.vector.VectorStoreService;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * B1 四刀之一（§8.1 规则 3）：share 派生只读镜像直测。
 * <p>
 * 写点仅 KnowledgeInfoServiceImpl#insertByBo/updateByBo（经
 * applySensitivityShareMirror 收口）：insert 缺省 INTERNAL；显式 sensitivity
 * 归一校验后按 PUBLIC→1 / 其余→0 派生 share（客户端传值一律被覆盖）；
 * update 未携带 sensitivity 时 share 置 null（MyBatis-Plus NOT_NULL 策略不进 SET，
 * 防「只改名字把 SECRET 库降级」与「客户端单独改 share」两类脱钩）。
 * SECRET 纪律：镜像逻辑永不产出 SECRET（仅显式人审值透传）。
 * <p>
 * insertByBo 依赖 MapstructUtils（Spring 上下文）无法纯单测，
 * 与 KnowledgeInfoServiceImplWrapperTest 同做法反射直测私有方法。
 */
@Tag("dev")
class KnowledgeInfoSensitivityMirrorTest {

    private static KnowledgeInfoServiceImpl newService() {
        return new KnowledgeInfoServiceImpl(
            mock(KnowledgeInfoMapper.class),
            mock(KnowledgeAttachMapper.class),
            mock(KnowledgeFragmentMapper.class),
            mock(VectorStoreService.class),
            mock(KnowledgeRetrievalService.class),
            mock(OssService.class),
            mock(KnowledgeAccessGate.class));
    }

    private static void applyMirror(KnowledgeInfo entity, boolean insert) throws Exception {
        Method method = KnowledgeInfoServiceImpl.class
            .getDeclaredMethod("applySensitivityShareMirror", KnowledgeInfo.class, boolean.class);
        method.setAccessible(true);
        method.invoke(newService(), entity, insert);
    }

    private static Long derivedShare(String sensitivity) throws Exception {
        Method method = KnowledgeInfoServiceImpl.class.getDeclaredMethod("derivedShare", String.class);
        method.setAccessible(true);
        return (Long) method.invoke(null, sensitivity);
    }

    private static void validate(KnowledgeInfo entity) throws Exception {
        Method method = KnowledgeInfoServiceImpl.class
            .getDeclaredMethod("validEntityBeforeSave", KnowledgeInfo.class);
        method.setAccessible(true);
        method.invoke(newService(), entity);
    }

    @Test
    void insertDefaultsToInternalWithShareZero() throws Exception {
        // 新建库未显式定级 → INTERNAL（等价旧 share=0）；现网 1 行 share=0 与此一致
        KnowledgeInfo entity = new KnowledgeInfo();
        applyMirror(entity, true);
        assertEquals("INTERNAL", entity.getSensitivity());
        assertEquals(0L, entity.getShare());
    }

    @Test
    void publicSensitivityOverridesClientShare() throws Exception {
        KnowledgeInfo entity = new KnowledgeInfo();
        entity.setSensitivity("PUBLIC");
        entity.setShare(0L); // 客户端伪造的 share，必须被镜像覆盖
        applyMirror(entity, true);
        assertEquals("PUBLIC", entity.getSensitivity());
        assertEquals(1L, entity.getShare(), "share 只读镜像，客户端值必须被派生值覆盖");
    }

    @Test
    void secretOnlySurvivesExplicitHumanReview() throws Exception {
        KnowledgeInfo entity = new KnowledgeInfo();
        entity.setSensitivity("SECRET");
        applyMirror(entity, true);
        assertEquals("SECRET", entity.getSensitivity(), "SECRET 仅人审显式设置时透传");
        assertEquals(0L, entity.getShare());
    }

    @Test
    void lowercaseInputIsNormalized() throws Exception {
        KnowledgeInfo entity = new KnowledgeInfo();
        entity.setSensitivity("public");
        applyMirror(entity, true);
        assertEquals("PUBLIC", entity.getSensitivity());
        assertEquals(1L, entity.getShare());
    }

    @Test
    void updateWithoutSensitivityNullsShare() throws Exception {
        // update 未携带 sensitivity：share=null → NOT_NULL 策略不进 SET，库内两键保持原值
        KnowledgeInfo entity = new KnowledgeInfo();
        entity.setSensitivity(null);
        entity.setShare(1L); // 客户端单独改 share 的尝试
        applyMirror(entity, false);
        assertNull(entity.getSensitivity());
        assertNull(entity.getShare(), "update 未携带 sensitivity 时 share 不得单独落库");
    }

    @Test
    void illegalSensitivityIsRejected() throws Exception {
        KnowledgeInfo entity = new KnowledgeInfo();
        entity.setSensitivity("TOP_SECRET");
        Exception thrown = assertThrows(Exception.class, () -> applyMirror(entity, true));
        assertTrue(thrown.getCause() instanceof ServiceException, "拒绝语义应为业务异常");
        assertTrue(thrown.getCause().getMessage().contains("TOP_SECRET"));
    }

    @Test
    void derivedShareMapsOnlyPublicToOne() throws Exception {
        assertEquals(1L, derivedShare("PUBLIC"));
        assertEquals(0L, derivedShare("INTERNAL"));
        assertEquals(0L, derivedShare("SECRET"));
    }

    @Test
    void validatorRejectsDecoupledShare() throws Exception {
        // 一致性断言：显式 sensitivity 时 share 必须等于派生值（防未来新增写点绕过镜像）
        KnowledgeInfo decoupled = new KnowledgeInfo();
        decoupled.setSensitivity("PUBLIC");
        decoupled.setShare(0L);
        Exception thrown = assertThrows(Exception.class, () -> validate(decoupled));
        assertTrue(thrown.getCause() instanceof ServiceException);

        KnowledgeInfo consistent = new KnowledgeInfo();
        consistent.setSensitivity("PUBLIC");
        consistent.setShare(1L);
        validate(consistent); // 一致即放行（分块参数 null 走默认 1000/50 合法）

        KnowledgeInfo noSensitivity = new KnowledgeInfo();
        noSensitivity.setSensitivity(null);
        noSensitivity.setShare(1L);
        validate(noSensitivity); // 未显式定级不做断言（历史行兼容）
    }
}
