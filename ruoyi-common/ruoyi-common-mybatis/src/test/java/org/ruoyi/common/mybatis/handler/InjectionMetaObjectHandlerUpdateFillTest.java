package org.ruoyi.common.mybatis.handler;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * R240 DEFECT 0493f098 契约单测：updateFill 必须与 insertFill 对称，不得无条件覆盖
 * 服务层已显式绑定的 updateBy（IPD /api/v1 无框架 LoginUser 上下文时曾把 actor.id() 吞成 -1）。
 *
 * @author autonomous-engineering-agent
 */
@Tag("dev")
class InjectionMetaObjectHandlerUpdateFillTest {

    private final InjectionMetaObjectHandler handler = new InjectionMetaObjectHandler();

    @Test
    void updateFill_keepsServiceBoundActor_whenUpdateByPreset() {
        BaseEntity entity = new BaseEntity();
        // 模拟 IPD 服务层 GateElementService.update 内 setUpdateBy(actor.id()) 的显式绑定
        entity.setUpdateBy(900101L);
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.updateFill(metaObject);

        // 契约：护栏应尊重服务层预置 actor，不得被吞成 -1
        assertEquals(900101L, entity.getUpdateBy(), "预置 updateBy 不应被 updateFill 无条件覆盖");
        // updateTime 仍应每次刷新（保持既有语义）
        assertNotNull(entity.getUpdateTime(), "updateTime 仍应每次自动填充");
    }

    @Test
    void updateFill_fillsDefault_whenUpdateByNullAndNoLogin() {
        BaseEntity entity = new BaseEntity();
        // updateBy 保持 null，且无 sa-token 登录上下文（getUserId() 返回 null）
        MetaObject metaObject = SystemMetaObject.forObject(entity);

        handler.updateFill(metaObject);

        // 契约：未预置且未登录时框架默认注入 -1，行为与修复前一致（向后兼容）
        assertEquals(-1L, entity.getUpdateBy(), "未预置且未登录时保持框架既有 -1 行为");
    }
}
