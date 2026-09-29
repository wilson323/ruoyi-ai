package org.ruoyi.service.knowledge.retriever;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.service.chat.impl.ChatServiceFacade;
import org.ruoyi.websocket.chat.MpChatWebSocketHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 四刀之四（C2 收敛）防回归：两调用方不得再各自持有同构
 * buildMultiKnowledgeAugmentor / CompositeContentRetriever 私有副本，
 * 唯一实现收敛在 MultiKnowledgeAugmentorFactory。
 * 结构断言（反射扫描 declaredMethods），纯类元数据不触发依赖加载。
 */
@Tag("dev")
class AugmentorConvergenceGuardTest {

    private static List<String> declaredMethodNames(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods()).map(Method::getName).toList();
    }

    @Test
    void facadeNoLongerDeclaresPrivateAugmentorBuilder() {
        List<String> names = declaredMethodNames(ChatServiceFacade.class);
        assertFalse(names.contains("buildMultiKnowledgeAugmentor"),
            "ChatServiceFacade 不得保留私有 buildMultiKnowledgeAugmentor（应委托工厂），实际=" + names);
        assertFalse(names.stream().anyMatch(n -> n.equals("retrieve") || n.contains("Composite")),
            "ChatServiceFacade 不得保留复合检索器内部类方法面");
    }

    @Test
    void wsHandlerNoLongerDeclaresPrivateAugmentorBuilder() {
        List<String> names = declaredMethodNames(MpChatWebSocketHandler.class);
        assertFalse(names.contains("buildMultiKnowledgeAugmentor"),
            "MpChatWebSocketHandler 不得保留私有 buildMultiKnowledgeAugmentor（应委托工厂），实际=" + names);
        assertFalse(names.stream().anyMatch(n -> n.contains("Composite")),
            "MpChatWebSocketHandler 不得保留复合检索器内部类方法面");
    }

    @Test
    void factoryIsTheSinglePublicImplementation() {
        boolean hasPublicBuilder = Arrays.stream(MultiKnowledgeAugmentorFactory.class.getMethods())
            .anyMatch(m -> m.getName().equals("buildMultiKnowledgeAugmentor")
                && Modifier.isPublic(m.getName().contains("build") ? m.getModifiers() : 0));
        assertTrue(hasPublicBuilder, "工厂须公开 buildMultiKnowledgeAugmentor 供两调用方委托");
        // 工厂是 Spring 组件（org.springframework.stereotype.Component 注解在场）
        assertTrue(MultiKnowledgeAugmentorFactory.class
            .isAnnotationPresent(org.springframework.stereotype.Component.class));
    }
}
