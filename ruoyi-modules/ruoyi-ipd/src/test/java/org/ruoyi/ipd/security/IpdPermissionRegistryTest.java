package org.ruoyi.ipd.security;

import cn.dev33.satoken.exception.NotPermissionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.controller.DeletionRequestController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R234（2026-09-27 owner 拍板「权限码全套开工」）：权限码册 ↔ Catalog 对账 + 403 拒绝通路稳定性。
 *
 * <p>三件事（历史教训：码没进 Catalog 导致全员 403 / 前端按钮闸永不满足）：
 * <ol>
 *   <li>反射提取 {@link IpdPermissionCode} 全部常量值，断言无重复字面值；</li>
 *   <li>断言每个码都被 {@link IpdRolePermissionCatalog} 登记（Java 默认目录），未登记即 fail；</li>
 *   <li>抽样验证 NotPermissionException → 403 {code:30001} 包络稳定（非 401/500）。</li>
 * </ol>
 *
 * <p><b>注意</b>：必须 {@code @Tag("dev")}——本仓 Surefire 按 {@code <groups>${profiles.active}</groups>}
 * 过滤，不加 tag 会被静默跳过（假绿）。
 */
@Tag("dev")
@DisplayName("R234: 权限码册 ↔ Catalog 对账 + 403 拒绝通路")
class IpdPermissionRegistryTest {

    /** 4 个内部角色（对齐 IpdRolePermissionCatalog.BY_ROLE / RnewPermissionContractTest 口径）。 */
    private static final List<String> INTERNAL_ROLES =
        List.of("SUPER_ADMIN", "GROUP_LEADER", "MARKET_PM", "RD_PM");

    /**
     * 刻意不登记 Catalog 的负向契约白名单（value → 依据）。
     *
     * <p>当前唯一条目：{@code ipd:switching-acceptance:admin}——owner 2026-09-07 拍板
     * （R-NEW-SEC-5 拆码为 _LOCK/_UNLOCK），2026-09-xx R186 双轨收敛核实锁定为
     * <b>负向契约常量·禁止登记</b>，由 {@code RnewPermissionContractTest}
     * {@code switchingAcceptanceAdminAliasNotRegistered} 双锁（任何内部角色都不持有、
     * Controller 不得引用）。owner 若决定正式启用别名，需手动补登 catalog 并同步改
     * RnewPermissionContractTest 两条契约与本白名单。
     *
     * <p>同理：若未来 {@link IpdPermissionCode} 出现多个常量刻意共享同一字面量，
     * 须把共享值加入 {@link #INTENTIONAL_SHARED_LITERALS} 并注明 owner 拍板日期。
     */
    private static final Map<String, String> UNREGISTERED_WHITELIST = Map.of(
        "ipd:switching-acceptance:admin",
        "owner 2026-09-07 R-NEW-SEC-5 拆码拍板 + R186 双轨收敛核实：负向契约常量，刻意不登记（RnewPermissionContractTest 双锁）"
    );

    /** 刻意共享同一字面值的白名单（当前为空：IpdPermissionCode 零重复）。新共享须注明 owner 拍板日期。 */
    private static final Set<String> INTENTIONAL_SHARED_LITERALS = Set.of();

    /** 反射提取 IpdPermissionCode 全部 String 常量值（含重复，按声明顺序）。 */
    private static List<String> allLiteralValues() {
        List<String> values = new ArrayList<>();
        for (Field f : IpdPermissionCode.class.getDeclaredFields()) {
            if (f.getType() != String.class || !Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            try {
                values.add((String) f.get(null));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("读取 IpdPermissionCode 常量失败: " + f.getName(), e);
            }
        }
        return values;
    }

    /** Java 默认目录（不含 DB 覆盖层）下 4 内部角色并集——「已登记」的判定基准。 */
    private static Set<String> registeredUnion() {
        Set<String> union = new LinkedHashSet<>();
        for (String role : INTERNAL_ROLES) {
            union.addAll(IpdRolePermissionCatalog.defaultPermissionsOf(role));
        }
        return union;
    }

    @Test
    @DisplayName("1) IpdPermissionCode 全部字面值零重复（刻意共享须显式白名单 + owner 拍板日期）")
    void allLiteralValuesAreUnique() {
        List<String> values = allLiteralValues();
        assertThat(values).as("IpdPermissionCode 不应为空").isNotEmpty();

        Map<String, Integer> counts = new HashMap<>();
        for (String v : values) {
            counts.merge(v, 1, Integer::sum);
        }
        Set<String> duplicates = new TreeSet<>();
        counts.forEach((v, n) -> {
            if (n > 1) {
                duplicates.add(v);
            }
        });

        Set<String> unexpected = new TreeSet<>(duplicates);
        unexpected.removeAll(INTENTIONAL_SHARED_LITERALS);
        assertThat(unexpected)
            .as("IpdPermissionCode 存在重复字面值——改名时会漏改（2026-09-06 常量化收口决议）；"
                + "若刻意共享须加入 INTENTIONAL_SHARED_LITERALS 并注释 owner 拍板日期")
            .isEmpty();

        // 白名单条目必须真实存在（防止白名单腐烂成死条目洗绿）
        for (String allowed : INTENTIONAL_SHARED_LITERALS) {
            assertThat(counts.getOrDefault(allowed, 0))
                .as("白名单字面值 %s 应真实存在于 IpdPermissionCode", allowed).isGreaterThan(1);
        }
    }

    @Test
    @DisplayName("2) 每个权限码都被 IpdRolePermissionCatalog 登记（未登记即 fail——403 实际生效前提）")
    void everyPermissionCodeIsRegisteredInCatalog() {
        Set<String> registered = registeredUnion();
        Set<String> unregistered = new TreeSet<>();
        for (String value : new TreeSet<>(new LinkedHashSet<>(allLiteralValues()))) {
            if (!registered.contains(value) && !UNREGISTERED_WHITELIST.containsKey(value)) {
                unregistered.add(value);
            }
        }
        assertThat(unregistered)
            .as("以下码未登记进 IpdRolePermissionCatalog 任何内部角色集合——注解若引用会全员 403 "
                + "（2026-09-06 SOP/AI 模型读码事故同款）；若刻意不登记须入 UNREGISTERED_WHITELIST 并注明 owner 拍板日期")
            .isEmpty();

        // 白名单条目必须真实存在于码册且确实未登记（防白名单腐烂洗绿）
        Set<String> allValues = new TreeSet<>(allLiteralValues());
        for (String allowed : UNREGISTERED_WHITELIST.keySet()) {
            assertThat(allValues).as("白名单码 %s 应真实存在于 IpdPermissionCode", allowed).contains(allowed);
            assertThat(registered)
                .as("白名单码 %s 必须仍未登记（Rnew 契约 switchingAcceptanceAdminAliasNotRegistered）", allowed)
                .doesNotContain(allowed);
        }
    }

    @Test
    @DisplayName("2b) R234 三码与 gate-review:list 同集合：4 内部角色全部持有（前端按钮闸解锁）")
    void r234CodesRegisteredForAllInternalRoles() {
        for (String code : List.of(
            IpdPermissionCode.OPERATION_GATE_REVIEW_CREATE,
            IpdPermissionCode.OPERATION_GATE_REVIEW_APPROVE,
            IpdPermissionCode.OPERATION_HANDOVER_CANCEL)) {
            for (String role : INTERNAL_ROLES) {
                assertThat(IpdRolePermissionCatalog.defaultPermissionsOf(role))
                    .as("%s 应持有 %s（READ_SET 与 gate-review:list 同集合）", role, code).contains(code);
            }
        }
        assertThat(IpdPermissionCode.OPERATION_GATE_REVIEW_CREATE).isEqualTo("ipd:gate-review:add");
        assertThat(IpdPermissionCode.OPERATION_GATE_REVIEW_APPROVE).isEqualTo("ipd:gate-review:edit");
        assertThat(IpdPermissionCode.OPERATION_HANDOVER_CANCEL).isEqualTo("ipd:handover:cancel");
    }

    // ==================== 403 拒绝通路（仿 Sec03WithdrawSideChannelTest#invokeNotPermissionHandler）====================

    @Test
    @DisplayName("3a) 全局 advice：NotPermissionException → 403 {code:30001} 包络（非 401/500）")
    void globalAdviceNotPermissionReturns403Envelope() {
        IpdPermissionExceptionHandler advice = new IpdPermissionExceptionHandler();
        ResponseEntity<ApiV1Response<Void>> resp =
            advice.notPermission(new NotPermissionException("ipd:gate-review:add"));

        assertThat(resp.getStatusCode().value())
            .as("拒绝通路必须 403（不得塌 401/500）").isEqualTo(403);
        assertThat(resp.getStatusCode().value()).isNotIn(401, 500);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode())
            .as("包络业务码必须 30001 FORBIDDEN").isEqualTo(ApiV1ErrorCode.FORBIDDEN.getCode());
        assertThat(ApiV1ErrorCode.FORBIDDEN.getCode()).isEqualTo(30001);
    }

    @Test
    @DisplayName("3b) Controller 局部 handler（非 withdraw 方法）：NotPermissionException → 403 {code:30001} 就地包络（R219台账⑧回归锁）")
    void controllerLocalHandlerNotPermissionReturns403Envelope() throws Exception {
        DeletionRequestController controller = new DeletionRequestController(null, null, null);
        Method target = DeletionRequestController.class.getDeclaredMethod("myRequests");
        HandlerMethod handlerMethod = new HandlerMethod(controller, target);

        ResponseEntity<ApiV1Response<Void>> resp = controller.handleNotPermissionForWithdraw(
            new NotPermissionException("ipd:handover:cancel"), handlerMethod);

        assertThat(resp.getStatusCode().value())
            .as("非 withdraw 方法的 NotPermission 必须就地返回 403（rethrow 会塌 500 裸体）").isEqualTo(403);
        assertThat(resp.getStatusCode().value()).isNotIn(401, 500);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(ApiV1ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    @DisplayName("3c) 拒绝包络与全局 advice 字节级同款：局部 handler 与 IpdPermissionExceptionHandler 返回同 code/message")
    void localAndGlobalEnvelopeAreIdentical() {
        IpdPermissionExceptionHandler advice = new IpdPermissionExceptionHandler();
        ApiV1Response<Void> global = advice.notPermission(
            new NotPermissionException("ipd:deletion-request:admin")).getBody();

        ApiV1Response<Void> local;
        try {
            DeletionRequestController controller = new DeletionRequestController(null, null, null);
            Method target = DeletionRequestController.class.getDeclaredMethod("myRequests");
            local = controller.handleNotPermissionForWithdraw(
                new NotPermissionException("ipd:deletion-request:admin"),
                new HandlerMethod(controller, target)).getBody();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThat(global).isNotNull();
        assertThat(local).isNotNull();
        assertThat(local.getCode()).isEqualTo(global.getCode());
        assertThat(local.getMessage()).isEqualTo(global.getMessage());
        assertThat(Collections.singletonList(local.getCode()))
            .containsExactly(30001);
    }
}
