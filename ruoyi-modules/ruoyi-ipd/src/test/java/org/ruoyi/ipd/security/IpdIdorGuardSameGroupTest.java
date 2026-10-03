package org.ruoyi.ipd.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-NEW A-2：IpdIdorGuard.assertSameGroupIpd(IpdActor, Long) 守卫 6 专项单测。
 *
 * <p>覆盖 9 个边界：null actor / null id / SUPER_ADMIN 豁免 / MARKET_PM 同组通过 / 跨组拒 /
 * GROUP_LEADER 同组通过 / actor.groupId null 拒 / objectGroupId null 拒 / 两侧 null 拒。
 *
 * <p>SG-10~SG-12 为统一口径 LEAD-GROUP-01 补格（2026-10-03）：锁定「组长跨组一律拒、与普通 PM
 * 同口径、SUPER_ADMIN 是唯一豁免」，防止再出现 BidP231Validator 式的组长从宽旁路。
 *
 * <p>核心断言：错误码恒等 {@link ApiV1ErrorCode#FORBIDDEN}；文案精确等 {@code "无权操作"}
 * （W4-Security 决策 3 锁定 FORBIDDEN 文案，不区分资源不存在与无权限——不泄漏存在性）。
 *
 * <p>纯 JVM 单测（无 Spring 上下文）。{@code LoginHelper.getTenantId()} 在租户禁用或未登录场景下
 * 返回 null，故跨租户分支（{@link IpdIdorGuard#requireTenantMatch}）不影响守卫 6 判定。
 */
@Tag("dev")
@DisplayName("R-NEW A-2 IpdIdorGuard.assertSameGroupIpd 守卫 6 边界")
class IpdIdorGuardSameGroupTest {

    @Test
    @DisplayName("SG-1 null actor → UNAUTHORIZED「未登录」")
    void nullActor_unauthorized() {
        assertThatThrownBy(() -> IpdIdorGuard.assertSameGroupIpd(null, 100L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessage("未登录")
            .extracting("errorCode").isEqualTo(ApiV1ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SG-2 actor.id() == null → UNAUTHORIZED「未登录」")
    void actorIdNull_unauthorized() {
        IpdActor actor = new IpdActor(null, "phantom", "MARKET_PM", 5L);
        assertThatThrownBy(() -> IpdIdorGuard.assertSameGroupIpd(actor, 100L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessage("未登录")
            .extracting("errorCode").isEqualTo(ApiV1ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SG-3 SUPER_ADMIN + actor.groupId=null + objectGroupId 任意 → 通过（豁免）")
    void superAdmin_groupIdNull_passes() {
        IpdActor admin = new IpdActor(1L, "root", "SUPER_ADMIN", null);
        assertThatCode(() -> IpdIdorGuard.assertSameGroupIpd(admin, 100L))
            .doesNotThrowAnyException();
        assertThatCode(() -> IpdIdorGuard.assertSameGroupIpd(admin, 999L))
            .doesNotThrowAnyException();
        assertThatCode(() -> IpdIdorGuard.assertSameGroupIpd(admin, null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SG-4 MARKET_PM 同组（actor.groupId=5L == objectGroupId=5L）→ 通过")
    void marketPm_sameGroup_passes() {
        IpdActor actor = new IpdActor(11L, "market", "MARKET_PM", 5L);
        assertThatCode(() -> IpdIdorGuard.assertSameGroupIpd(actor, 5L))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SG-5 MARKET_PM 跨组（actor.groupId=5L ≠ objectGroupId=6L）→ FORBIDDEN「无权操作」")
    void marketPm_crossGroup_forbidden() {
        IpdActor actor = new IpdActor(11L, "market", "MARKET_PM", 5L);
        assertThatThrownBy(() -> IpdIdorGuard.assertSameGroupIpd(actor, 6L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessage("无权操作")
            .extracting("errorCode").isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("SG-6 GROUP_LEADER 同组 → 通过")
    void groupLeader_sameGroup_passes() {
        IpdActor actor = new IpdActor(21L, "leader", "GROUP_LEADER", 8L);
        assertThatCode(() -> IpdIdorGuard.assertSameGroupIpd(actor, 8L))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SG-7 GROUP_LEADER actor.groupId=null → FORBIDDEN「无权操作」（不豁免，groupId 必填）")
    void groupLeader_groupIdNull_forbidden() {
        IpdActor actor = new IpdActor(21L, "leader", "GROUP_LEADER", null);
        assertThatThrownBy(() -> IpdIdorGuard.assertSameGroupIpd(actor, 8L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessage("无权操作")
            .extracting("errorCode").isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("SG-8 actor.groupId 存在但 objectGroupId=null → FORBIDDEN「无权操作」")
    void objectGroupIdNull_forbidden() {
        IpdActor actor = new IpdActor(11L, "market", "MARKET_PM", 5L);
        assertThatThrownBy(() -> IpdIdorGuard.assertSameGroupIpd(actor, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessage("无权操作")
            .extracting("errorCode").isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("SG-9 两侧均 null → FORBIDDEN「无权操作」（null 永远拒绝，无「都 null 即放行」语义）")
    void bothNull_forbidden() {
        IpdActor actor = new IpdActor(11L, "market", "MARKET_PM", null);
        assertThatThrownBy(() -> IpdIdorGuard.assertSameGroupIpd(actor, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessage("无权操作")
            .extracting("errorCode").isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    // ===== 统一口径 LEAD-GROUP-01（组长跨组不放行）2026-10-03 =====
    // 依据 BR-ORG-06 三层权限矩阵：组长在「查看/编辑项目、删除初审、导出审计」四行一律「本组」，
    // 「全部」只属 SUPER_ADMIN；BR-ORG-05 的跨组能力仅限「可查看」；G-09 不做代理组长。
    // SG-6（同组通过）已覆盖组长同组路径，本段补齐「组长跨组必拒」——此前测试矩阵缺此格，
    // 正是 BidP231Validator 手写「组长无条件放行」能长期存活而无人发现的原因。

    @Test
    @DisplayName("SG-10 GROUP_LEADER 跨组（actor.groupId=8L ≠ objectGroupId=9L）→ FORBIDDEN（无豁免）")
    void groupLeader_crossGroup_forbidden() {
        IpdActor actor = new IpdActor(21L, "leader", "GROUP_LEADER", 8L);
        assertThatThrownBy(() -> IpdIdorGuard.assertSameGroupIpd(actor, 9L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessage("无权操作")
            .extracting("errorCode").isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("SG-11 组长与普通 PM 跨组口径恒等（同组过 / 跨组拒），不受角色影响")
    void groupLeaderAndMarketPm_shareIdenticalCrossGroupSemantics() {
        IpdActor leader = new IpdActor(21L, "leader", "GROUP_LEADER", 8L);
        IpdActor marketPm = new IpdActor(11L, "market", "MARKET_PM", 8L);
        // 同组：两者都过
        assertThatCode(() -> IpdIdorGuard.assertSameGroupIpd(leader, 8L)).doesNotThrowAnyException();
        assertThatCode(() -> IpdIdorGuard.assertSameGroupIpd(marketPm, 8L)).doesNotThrowAnyException();
        // 跨组：两者都拒，错误码与文案完全一致（不存在「组长更宽」的分支）
        assertThatThrownBy(() -> IpdIdorGuard.assertSameGroupIpd(leader, 9L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessage("无权操作")
            .extracting("errorCode").isEqualTo(ApiV1ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> IpdIdorGuard.assertSameGroupIpd(marketPm, 9L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessage("无权操作")
            .extracting("errorCode").isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("SG-12 SUPER_ADMIN 对任意组（含 null）无条件豁免——口径中唯一豁免角色")
    void superAdmin_isTheOnlyExemptRole() {
        IpdActor admin = new IpdActor(1L, "root", "SUPER_ADMIN", 77L);
        assertThatCode(() -> IpdIdorGuard.assertSameGroupIpd(admin, 8L)).doesNotThrowAnyException();
        assertThatCode(() -> IpdIdorGuard.assertSameGroupIpd(admin, 999L)).doesNotThrowAnyException();
        assertThatCode(() -> IpdIdorGuard.assertSameGroupIpd(admin, null)).doesNotThrowAnyException();
    }
}