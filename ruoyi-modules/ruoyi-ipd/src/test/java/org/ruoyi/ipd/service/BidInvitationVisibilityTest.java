package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.mapper.BidInvitationMapper;
import org.ruoyi.ipd.mapper.BidResponseMapper;
import org.ruoyi.ipd.security.IpdActor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 招标单**读取可见性**单测（@Tag("dev")）。
 *
 * <p>覆盖项：验收清单 AC-TEAM-01 原文「市场PM 发起一对一邀标给研发PM A | A 收到通知；
 * <b>其他研发PM 看不到该招标单</b>」。
 *
 * <p>2026-10-03 前的现状：`GET /bid-invitations`（列表）与 `GET /bid-invitations/{id}`（详情）
 * 只做 `requireInternal()`（是不是内部人），**没有任何对象级判定**；而两者用的权限码
 * `ipd:project:list` / `ipd:project:query` 在四角色目录里**人人都有** ⇒ 定向邀标单全文
 * （含金额、需求描述、受邀人）对任意内部用户可读。故本类同时锁两个方向：
 * <ul>
 *   <li>正例（反向锁）：受邀人 / 发起人 / PUBLIC 模式 / 超管 —— 必须放行；</li>
 *   <li>反例：同角色但无关的第三人 —— 必须 FORBIDDEN。</li>
 * </ul>
 *
 * <p>纯 JVM 单测无 MP 运行时，@BeforeAll 手动初始化 BidInvitation 的 lambda 列缓存
 * （列表谓词捕获需要列名解析）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@DisplayName("AC-TEAM-01 招标单读取可见性（定向邀标不得被无关人员读到）")
class BidInvitationVisibilityTest {

    private static final Long INVITATION_ID = 1L;
    private static final Long PROJECT_ID = 300L;
    private static final Long CREATOR = 11L;
    private static final Long INVITEE = 22L;
    private static final Long STRANGER = 33L;
    private static final String MODE_ONE_TO_ONE = "ONE_TO_ONE";
    private static final String MODE_PUBLIC = "PUBLIC";

    @Mock
    private BidInvitationMapper bidInvitationMapper;
    @Mock
    private BidResponseMapper bidResponseMapper;
    @Mock
    private IAuditLogService auditLogService;
    @Mock
    private NotificationService notificationService;

    private BidInvitationService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), BidInvitation.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new BidInvitationService(
            bidInvitationMapper, bidResponseMapper, auditLogService, notificationService);
    }

    /** 同角色（RD_PM）的普通人——用来证明「有权限码 ≠ 能看这条数据」。 */
    private static IpdActor rdPm(long id) {
        return new IpdActor(id, "研发PM" + id, "RD_PM", 5L);
    }

    private static IpdActor superAdmin() {
        return new IpdActor(1L, "超管", "SUPER_ADMIN", null);
    }

    private static BidInvitation invitation(Long createBy, Long targetPersonId, String mode) {
        BidInvitation inv = new BidInvitation();
        inv.setId(INVITATION_ID);
        inv.setProjectId(PROJECT_ID);
        inv.setCreateBy(createBy);
        inv.setTargetPersonId(targetPersonId);
        inv.setMode(mode);
        return inv;
    }

    private void givenOneToOneInvitation() {
        when(bidInvitationMapper.selectById(INVITATION_ID))
            .thenReturn(invitation(CREATOR, INVITEE, MODE_ONE_TO_ONE));
    }

    // ==================== 详情：getVisibleTo ====================

    @Test
    @DisplayName("AC-TEAM-01 反向锁：受邀人（targetPersonId）必须能读到定向邀标")
    void getVisibleTo_invitee_allowed() {
        givenOneToOneInvitation();

        assertThat(service.getVisibleTo(INVITATION_ID, rdPm(INVITEE))).isNotNull();
    }

    @Test
    @DisplayName("AC-TEAM-01 反例：其他研发PM 看不到该招标单 → FORBIDDEN")
    void getVisibleTo_stranger_forbidden() {
        givenOneToOneInvitation();

        assertThatThrownBy(() -> service.getVisibleTo(INVITATION_ID, rdPm(STRANGER)))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("无权查看该招标单")
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("可见性：发起人可读自己发起的定向邀标")
    void getVisibleTo_creator_allowed() {
        givenOneToOneInvitation();

        assertThat(service.getVisibleTo(INVITATION_ID, rdPm(CREATOR))).isNotNull();
    }

    @Test
    @DisplayName("可见性：PUBLIC 模式本就公开征集 → 全员可读")
    void getVisibleTo_publicMode_allowed() {
        when(bidInvitationMapper.selectById(INVITATION_ID))
            .thenReturn(invitation(CREATOR, null, MODE_PUBLIC));

        assertThat(service.getVisibleTo(INVITATION_ID, rdPm(STRANGER))).isNotNull();
    }

    @Test
    @DisplayName("可见性：超管可读任意招标单")
    void getVisibleTo_superAdmin_allowed() {
        givenOneToOneInvitation();

        assertThat(service.getVisibleTo(INVITATION_ID, superAdmin())).isNotNull();
    }

    @Test
    @DisplayName("可见性：记录不存在 → NOT_FOUND（既有行为不变）")
    void getVisibleTo_missing_notFound() {
        when(bidInvitationMapper.selectById(INVITATION_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.getVisibleTo(INVITATION_ID, rdPm(INVITEE)))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.NOT_FOUND);
    }

    // ==================== 列表：page ====================

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<BidInvitation> capturePagePredicate(IpdActor actor, String status) {
        when(bidInvitationMapper.selectPage(any(), any())).thenReturn(new Page<>());
        service.page(actor, 1, 20, PROJECT_ID, status);
        ArgumentCaptor<LambdaQueryWrapper<BidInvitation>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(bidInvitationMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("列表：非超管必须带可见性谓词（受邀人 / 发起人 / PUBLIC 三选一）")
    void page_nonSuperAdmin_appliesVisibilityPredicate() {
        String sql = capturePagePredicate(rdPm(STRANGER), null).getSqlSegment();

        assertThat(sql).contains("target_person_id");
        assertThat(sql).contains("create_by");
        assertThat(sql).contains("mode");
        // 三条件必须被 and(...) 整体括起来，否则 or 会把 projectId / status 过滤短路掉
        assertThat(sql).contains("AND (");
    }

    @Test
    @DisplayName("列表：超管不加可见性谓词（看全量）")
    void page_superAdmin_noVisibilityPredicate() {
        String sql = capturePagePredicate(superAdmin(), null).getSqlSegment();

        assertThat(sql).doesNotContain("target_person_id");
    }

    @Test
    @DisplayName("列表：未认证（actor.id 为空）→ UNAUTHORIZED")
    void page_nullActor_unauthorized() {
        assertThatThrownBy(() -> service.page(new IpdActor(null, "无名", "RD_PM", 5L), 1, 20, null, null))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("列表：项目 + 状态过滤仍生效（可见性谓词不得把它们顶掉）")
    void page_keepsProjectAndStatusFilters() {
        String sql = capturePagePredicate(rdPm(STRANGER), "PENDING").getSqlSegment();

        assertThat(sql).contains("project_id");
        assertThat(sql).contains("status");
    }

    /** 编译期防呆：page 的返回类型未变。 */
    private static void pageReturnType(IPage<BidInvitation> ignored) {
    }
}
