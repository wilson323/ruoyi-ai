/**
 * RecoveryWarningController 写口归属校验（横向越权防护）测试。
 *
 * <p>背景：POST /api/v1/recovery/check-90d 此前只做角色门（requireLeaderOrAdmin），
 * actor 直接丢弃，service 无条件 selectList 全库项目并逐个 insert 预警行——
 * 任一组长都能对别的组项目批量写数据。
 *
 * <p>范式（照 AiDocumentController.requireVersionOnPathChain）：
 * actor 只来自会话（不接受入参）→ 归属解析在写库前（service 内逐项目比 main_group_id）→
 * 失败即拒不泄漏存在性。
 */
package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.RecoveryWarningService;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class RecoveryWarningControllerOwnershipTest {

    /** 攻击者所在组。 */
    private static final Long ACTOR_GROUP = 777001L;

    @Mock private IpdPermission ipdPermission;
    /** 控制器字段声明的是实现类 RecoveryWarningService，mock 也必须按实现类声明才能注入。 */
    @Mock private RecoveryWarningService recoveryWarningService;

    @InjectMocks private RecoveryWarningController controller;

    private IpdActor leader() {
        return new IpdActor(1L, "组长", "GROUP_LEADER", ACTOR_GROUP);
    }

    @Test
    @DisplayName("actor 只来自会话：会话组长传给 service，客户端无法伪造归属")
    void actorComesFromSession_only() {
        LocalDate scanDate = LocalDate.of(2026, 9, 20);
        when(ipdPermission.requireLeaderOrAdmin()).thenReturn(leader());
        when(recoveryWarningService.checkAndGenerate(any(IpdActor.class), eq(scanDate))).thenReturn(3);

        assertThat(controller.check90d(scanDate).getData()).isEqualTo(3);

        ArgumentCaptor<IpdActor> captor = ArgumentCaptor.forClass(IpdActor.class);
        verify(recoveryWarningService).checkAndGenerate(captor.capture(), eq(scanDate));
        assertThat(captor.getValue().groupId()).isEqualTo(ACTOR_GROUP);
    }

    @Test
    @DisplayName("会话身份缺失 → UNAUTHORIZED，扫描不执行")
    void nullActor_rejected_beforeScan() {
        when(ipdPermission.requireLeaderOrAdmin()).thenReturn(null);

        assertThatThrownBy(() -> controller.check90d(LocalDate.of(2026, 9, 20)))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.UNAUTHORIZED));
        verify(recoveryWarningService, never()).checkAndGenerate(any(), any());
    }

    @Test
    @DisplayName("超管会话照常触发全库扫描（回归）")
    void superAdmin_stillScans() {
        LocalDate scanDate = LocalDate.of(2026, 9, 20);
        when(ipdPermission.requireLeaderOrAdmin())
            .thenReturn(new IpdActor(9L, "超管", "SUPER_ADMIN", ACTOR_GROUP));
        when(recoveryWarningService.checkAndGenerate(any(IpdActor.class), eq(scanDate))).thenReturn(7);

        assertThat(controller.check90d(scanDate).getData()).isEqualTo(7);
    }
}
