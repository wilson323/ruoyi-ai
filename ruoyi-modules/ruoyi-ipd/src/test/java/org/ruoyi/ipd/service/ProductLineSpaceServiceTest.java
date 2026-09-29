package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.ProductLineMember;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductLineMemberMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import java.util.List;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class ProductLineSpaceServiceTest {
    @Mock ProductLineMapper lines;
    @Mock ProductLineMemberMapper members;
    @Mock ProductMapper products;
    @Mock ProjectMapper projects;
    @Mock ProjectMemberMapper projectMembers;
    @Mock PersonMapper persons;
    @Mock IAuditLogService audit;
    ProductLineSpaceService service;

    private static final IpdActor ADMIN = new IpdActor(1L, "管理员", "SUPER_ADMIN", 1L);
    private static final IpdActor LEADER = new IpdActor(2L, "产品线组长", "MARKET_PM", 2L);
    private static final IpdActor APPLICANT = new IpdActor(3L, "申请人", "RD_PM", 3L);
    private static final IpdActor OTHER_GROUP_LEADER = new IpdActor(4L, "组织组长", "GROUP_LEADER", 4L);

    @BeforeAll
    static void tableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProductLine.class);
        TableInfoHelper.initTableInfo(assistant, ProductLineMember.class);
        TableInfoHelper.initTableInfo(assistant, Product.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
    }

    @BeforeEach
    void setUp() {
        service = new ProductLineSpaceService(lines, members, products, projects, projectMembers, persons, audit);
    }

    private static ProductLine line() {
        return ProductLine.builder().id(10L).lineCode("L1").lineName("产品线一")
            .leaderPersonId(LEADER.id()).status("ACTIVE").tenantId("000000").delFlag("0").build();
    }

    private static ProductLineMember member(Long personId, String status) {
        return ProductLineMember.builder().id(100L).productLineId(10L).personId(personId)
            .status(status).tenantId("000000").delFlag("0").build();
    }

    private static Person activePerson(Long id) {
        return Person.builder().id(id).tenantId("000000")
            .accountStatus("ACTIVE").employmentStatus("ACTIVE").build();
    }

    @Test
    void personCanApplyForSelfAndPendingIsIdempotent() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        when(persons.selectById(APPLICANT.id())).thenReturn(activePerson(APPLICANT.id()));
        when(members.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null, member(APPLICANT.id(), "PENDING"));
        ProductLineMember first = service.apply(10L, APPLICANT);
        ProductLineMember second = service.apply(10L, APPLICANT);
        assertThat(first.getPersonId()).isEqualTo(APPLICANT.id());
        assertThat(first.getStatus()).isEqualTo("PENDING");
        assertThat(second.getStatus()).isEqualTo("PENDING");
        verify(members).insert(first);
    }

    @Test
    void applicationRejectsInactiveOrForeignTenantPersonBeforeMemberWrite() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        when(persons.selectById(APPLICANT.id())).thenReturn(Person.builder().id(APPLICANT.id())
            .tenantId("other").accountStatus("ACTIVE").employmentStatus("ACTIVE").build());
        assertThatThrownBy(() -> service.apply(10L, APPLICANT))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("本租户在职");
        verify(members, never()).insert(any(ProductLineMember.class));
    }

    @Test
    void currentTenantCanDiscoverActiveSpacesBeforeJoining() {
        when(lines.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(line()));
        assertThat(service.discoverableLines(APPLICANT)).extracting(ProductLine::getId).containsExactly(10L);
        verify(members, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void nonAdminCannotRenameAndAdminRenameIsAudited() {
        assertThatThrownBy(() -> service.rename(10L, "新名称", APPLICANT))
            .isInstanceOf(IpdBusinessException.class);
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        when(lines.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertThat(service.rename(10L, " 新名称 ", ADMIN).getLineName()).isEqualTo("新名称");
        verify(audit).append(ADMIN, "PRODUCT_LINE_RENAME", "product_lines", 10L,
            "old=产品线一,new=新名称");
    }

    @Test
    void deactivationRejectsAssociatedProductOrPendingApplication() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        when(products.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L, 0L);
        when(members.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        assertThatThrownBy(() -> service.deactivate(10L, ADMIN)).isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("关联产品或待审批");
        assertThatThrownBy(() -> service.deactivate(10L, ADMIN)).isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("关联产品或待审批");
        verify(lines, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void deactivationRejectsEvenInactiveAssociatedProduct() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        when(products.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        assertThatThrownBy(() -> service.deactivate(10L, ADMIN))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("关联产品");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Product>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(products).selectCount(query.capture());
        assertThat(Product.STATUSES).contains(Product.ST_INACTIVE, Product.ST_ON_SALE, Product.ST_IN_RD);
        assertThat(query.getValue().getSqlSegment()).doesNotContain("status");
        assertThat(query.getValue().getParamNameValuePairs().values())
            .doesNotContain(Product.ST_INACTIVE, Product.ST_ON_SALE, Product.ST_ACTIVE);
        verify(lines, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void deactivationWithoutAssociatedProductOrApplicationIsAudited() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        when(products.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(members.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(lines.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertThat(service.deactivate(10L, ADMIN).getStatus()).isEqualTo("INACTIVE");
        verify(audit).append(ADMIN, "PRODUCT_LINE_DEACTIVATE", "product_lines", 10L, "L1");
    }

    @Test
    void leaderCannotLeaveUntilReplacementAndRegularMemberCanLeave() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        when(members.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(member(LEADER.id(), "ACTIVE"), member(APPLICANT.id(), "ACTIVE"));
        assertThatThrownBy(() -> service.leave(10L, LEADER))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("改派");
        when(members.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertThat(service.leave(10L, APPLICANT).getStatus()).isEqualTo("EXITED");
        verify(audit).append(org.mockito.ArgumentMatchers.eq(APPLICANT),
            org.mockito.ArgumentMatchers.eq("PRODUCT_LINE_MEMBER_LEAVE"),
            org.mockito.ArgumentMatchers.eq("product_line_members"),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("line=10"));
    }

    @Test
    void adminCanRemoveMemberButMustReassignLeaderFirst() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        when(members.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(member(LEADER.id(), "ACTIVE"), member(APPLICANT.id(), "ACTIVE"));
        assertThatThrownBy(() -> service.removeMember(10L, LEADER.id(), ADMIN))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("改派");
        when(members.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertThat(service.removeMember(10L, APPLICANT.id(), ADMIN).getStatus()).isEqualTo("EXITED");
        verify(audit).append(ADMIN, "PRODUCT_LINE_MEMBER_REMOVE", "product_line_members", 100L,
            "line=10,person=3");
    }

    @Test
    void onlyAppointedActiveLeaderOrAdminCanReview() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        assertThatThrownBy(() -> service.review(10L, APPLICANT.id(), true, OTHER_GROUP_LEADER))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("无权审批");
        verify(members, never()).update(isNull(), any(LambdaUpdateWrapper.class));

        when(members.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(member(LEADER.id(), "ACTIVE"), member(APPLICANT.id(), "PENDING"));
        when(persons.selectById(APPLICANT.id())).thenReturn(Person.builder().id(APPLICANT.id())
            .tenantId("000000").accountStatus("ACTIVE").employmentStatus("ACTIVE").build());
        when(members.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        ProductLineMember approved = service.review(10L, APPLICANT.id(), true, LEADER);
        assertThat(approved.getStatus()).isEqualTo("ACTIVE");
        assertThat(approved.getReviewedBy()).isEqualTo(LEADER.id());
    }

    @Test
    void adminCannotAppointNonMemberAsLeader() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        when(persons.selectById(APPLICANT.id())).thenReturn(Person.builder().id(APPLICANT.id())
            .tenantId("000000").accountStatus("ACTIVE").employmentStatus("ACTIVE").build());
        when(members.selectOne(any(LambdaQueryWrapper.class))).thenReturn(member(APPLICANT.id(), "PENDING"));
        assertThatThrownBy(() -> service.appointLeader(10L, APPLICANT.id(), ADMIN))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("在职成员");
        verify(lines, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void repeatedLeaderAppointmentDoesNotWriteTwice() {
        ProductLine line = line();
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line);
        when(persons.selectById(APPLICANT.id())).thenReturn(Person.builder().id(APPLICANT.id())
            .tenantId("000000").accountStatus("ACTIVE").employmentStatus("ACTIVE").build());
        when(members.selectOne(any(LambdaQueryWrapper.class))).thenReturn(member(APPLICANT.id(), "ACTIVE"));
        when(lines.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertThat(service.appointLeader(10L, APPLICANT.id(), ADMIN).getLeaderPersonId()).isEqualTo(3L);
        assertThat(service.appointLeader(10L, APPLICANT.id(), ADMIN).getLeaderPersonId()).isEqualTo(3L);
        verify(lines).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void productAssignmentKeepsManyProductsPerLineAndRejectsCrossLineReassignment() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        Product product = Product.builder().id(21L).productName("A").tenantId("000000").delFlag("0").build();
        when(products.selectById(21L)).thenReturn(product);
        when(products.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertThat(service.assignProduct(10L, 21L, ADMIN).getProductLineId()).isEqualTo(10L);
        product.setProductLineId(20L);
        assertThatThrownBy(() -> service.assignProduct(10L, 21L, ADMIN))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("状态已变化");
    }

    @Test
    void onlyAdminCanUnassignInactiveProductWithoutActiveProject() {
        assertThatThrownBy(() -> service.unassignProduct(10L, 21L, APPLICANT))
            .isInstanceOf(IpdBusinessException.class);
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        Product product = Product.builder().id(21L).productLineId(10L)
            .status(Product.ST_INACTIVE).tenantId("000000").build();
        when(products.selectOne(any(LambdaQueryWrapper.class))).thenReturn(product);
        when(projects.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(products.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertThat(service.unassignProduct(10L, 21L, ADMIN).getProductLineId()).isNull();
        verify(audit).append(ADMIN, "PRODUCT_LINE_PRODUCT_UNASSIGN", "products", 21L, "line=10");
    }

    @Test
    void onSaleProductCannotBeUnassigned() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        when(products.selectOne(any(LambdaQueryWrapper.class))).thenReturn(Product.builder()
            .id(21L).productLineId(10L).status(Product.ST_ON_SALE).tenantId("000000").build());
        assertThatThrownBy(() -> service.unassignProduct(10L, 21L, ADMIN))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("仅停用产品");
        verify(products, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void inactiveProductWithActiveProjectCannotBeUnassigned() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        when(products.selectOne(any(LambdaQueryWrapper.class))).thenReturn(Product.builder()
            .id(21L).productLineId(10L).projectId(31L)
            .status(Product.ST_INACTIVE).tenantId("000000").build());
        when(projects.selectById(31L)).thenReturn(Project.builder().id(31L).productId(21L)
            .tenantId("000000").status("ACTIVE").build());
        assertThatThrownBy(() -> service.unassignProduct(10L, 21L, ADMIN))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("活动项目");
        verify(products, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void archivedProjectLinkIsPreservedWhenProductIsUnassigned() {
        when(lines.selectOne(any(LambdaQueryWrapper.class))).thenReturn(line());
        Product product = Product.builder().id(21L).productLineId(10L).projectId(31L)
            .status(Product.ST_INACTIVE).tenantId("000000").build();
        when(products.selectOne(any(LambdaQueryWrapper.class))).thenReturn(product);
        when(projects.selectById(31L)).thenReturn(Project.builder().id(31L).productId(21L)
            .tenantId("000000").status("ARCHIVED").build());
        when(projects.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(products.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        Product unassigned = service.unassignProduct(10L, 21L, ADMIN);
        assertThat(unassigned.getProductLineId()).isNull();
        assertThat(unassigned.getProjectId()).isEqualTo(31L);
        verify(projects, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void teamMemberOnlySeesOwnProjectWorkspace() {
        when(lines.selectById(10L)).thenReturn(line());
        when(members.selectOne(any(LambdaQueryWrapper.class))).thenReturn(member(APPLICANT.id(), "ACTIVE"));
        when(products.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            Product.builder().id(21L).build(), Product.builder().id(22L).build()));
        when(projects.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            Project.builder().id(31L).productId(21L).build(),
            Project.builder().id(32L).productId(22L).build()));
        when(projectMembers.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            ProjectMember.builder().projectId(31L).personId(APPLICANT.id()).build()));
        assertThat(service.projects(10L, APPLICANT)).extracting(Project::getId).containsExactly(31L);
    }
}
