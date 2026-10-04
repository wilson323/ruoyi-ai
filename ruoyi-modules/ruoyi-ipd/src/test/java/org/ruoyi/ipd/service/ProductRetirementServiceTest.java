package org.ruoyi.ipd.service;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.*;
import org.ruoyi.ipd.dto.product.*;
import org.ruoyi.ipd.mapper.*;
import org.ruoyi.ipd.security.IpdActor;
import java.util.Date;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@Tag("dev") @ExtendWith(MockitoExtension.class)
class ProductRetirementServiceTest {
 @Mock ProductMapper products; @Mock ProductLineMapper lines; @Mock ProductLineMemberMapper members;
 @Mock AuditLogMapper auditRows; @Mock ProductRetirementMapper retirements; @Mock IAuditLogService audit;
 ProductRetirementService service; Product product; ProductLine line; ProductRetirement row;
 IpdActor proposer=new IpdActor(2L,"提交人","RD_PM",1L);
 IpdActor leader=new IpdActor(3L,"负责人","MARKET_PM",1L);
 @BeforeEach void setup(){
  for(Class<?> type:new Class[]{ProductRetirement.class,ProductLineMember.class,AuditLog.class,ProductLine.class})
   TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"retirement"),type);
  service=new ProductRetirementService(products,lines,members,retirements,audit,auditRows);
  product=Product.builder().id(10L).productLineId(20L).tenantId("000000").retirementLocked("0").build();
  line=ProductLine.builder().id(20L).status("ACTIVE").leaderPersonId(3L).tenantId("000000").build();
  row=new ProductRetirement(); row.setId(30L); row.setProductId(10L); row.setProposerId(2L);
  row.setTenantId("000000"); row.setVersion(0); row.setStatus("PENDING_RD_LEADER");
  lenient().when(products.selectById(10L)).thenReturn(product);
  lenient().when(lines.selectById(20L)).thenReturn(line);
  lenient().when(lines.selectOne(any())).thenReturn(line);
  lenient().when(members.selectCount(any())).thenReturn(1L);
  lenient().when(retirements.findForUpdate(10L,"000000")).thenReturn(row);
  lenient().when(retirements.selectOne(any())).thenReturn(row);
  lenient().when(retirements.update(isNull(),any())).thenReturn(1);
 }
 @Test void lineLeaderApprovesSingleStage(){
  when(products.approveRetirement(eq(10L),eq("000000"),any())).thenReturn(1);
  service.decide(10L,new RetirementDecisionReq(0,"APPROVE","同意"),leader);
  verify(products).approveRetirement(eq(10L),eq("000000"),any()); verify(audit).append(eq(leader),eq("APPROVE_RETIREMENT"),eq("product_retirements"),eq(30L),eq("同意"));
  assertThat(row.getVersion()).isEqualTo(1);
 }
 @Test void adminCannotReplaceExistingLeader(){
  assertThatThrownBy(()->service.decide(10L,new RetirementDecisionReq(0,"APPROVE",null),new IpdActor(1L,"管理员","SUPER_ADMIN",1L))).hasMessageContaining("负责人");
  verify(retirements,never()).update(isNull(),any()); verify(products,never()).approveRetirement(anyLong(),anyString(),any());
 }
 @Test void submitterCannotSelfApproveEvenWhenLeader(){
  line.setLeaderPersonId(2L);
  assertThatThrownBy(()->service.decide(10L,new RetirementDecisionReq(0,"APPROVE",null),proposer)).hasMessageContaining("自己的");
  verify(products,never()).approveRetirement(anyLong(),anyString(),any());
 }
 @Test void exitedLeaderCannotApprove(){
  when(members.selectCount(any())).thenReturn(0L);
  assertThatThrownBy(()->service.decide(10L,new RetirementDecisionReq(0,"APPROVE",null),leader)).hasMessageContaining("在职成员");
  verify(products,never()).approveRetirement(anyLong(),anyString(),any());
 }
 @Test void noLeaderUsesExistingAdminRule(){
  line.setLeaderPersonId(null); when(products.approveRetirement(eq(10L),eq("000000"),any())).thenReturn(1);
  service.decide(10L,new RetirementDecisionReq(0,"APPROVE",null),new IpdActor(1L,"管理员","SUPER_ADMIN",1L));
  verify(products).approveRetirement(eq(10L),eq("000000"),any());
 }
 @Test void rejectRequiresOpinionAndDoesNotLockProduct(){
  assertThatThrownBy(()->service.decide(10L,new RetirementDecisionReq(0,"REJECT",""),leader)).hasMessageContaining("意见");
  service.decide(10L,new RetirementDecisionReq(0,"REJECT","补充支持政策"),leader);
  verify(products,never()).approveRetirement(anyLong(),anyString(),any());
  verify(audit).append(eq(leader),eq("REJECT_RETIREMENT"),eq("product_retirements"),eq(30L),eq("补充支持政策"));
 }
 @Test void staleVersionCannotConsumeApproval(){
  row.setVersion(1);
  assertThatThrownBy(()->service.decide(10L,new RetirementDecisionReq(0,"APPROVE",null),leader)).hasMessageContaining("刷新");
  verify(products,never()).approveRetirement(anyLong(),anyString(),any());
 }
 @Test void alreadyApprovedCannotBeConsumedTwice(){
  row.setStatus("APPROVED");
  assertThatThrownBy(()->service.decide(10L,new RetirementDecisionReq(0,"APPROVE",null),leader)).hasMessageContaining("已处理");
  verify(products,never()).approveRetirement(anyLong(),anyString(),any());
 }
 @Test void rejectedRequestUsesSameRow(){
  row.setStatus("REJECTED");
  ProductRetirement result=service.submit(10L,new RetirementSubmitReq(0,"已补充"),proposer);
  assertThat(result.getId()).isEqualTo(30L); assertThat(result.getStatus()).isEqualTo("PENDING_RD_LEADER");
  verify(retirements,never()).insert(any(ProductRetirement.class));
 }
 @Test void firstSubmissionDoesNotClaimUnknownReadinessPassed(){
  when(retirements.findForUpdate(10L,"000000")).thenReturn(null); when(retirements.insert(any(ProductRetirement.class))).thenReturn(1);
  ProductRetirement result=service.submit(10L,new RetirementSubmitReq(0,"停止维护"),proposer);
  assertThat(result.getReadinessPassed()).isEqualTo("0"); assertThat(result.getReadinessOpenIssues()).isEqualTo(-1);
  verify(products,never()).approveRetirement(anyLong(),anyString(),any());
 }
 @Test void retiredHistoryCanReadButCannotModifyPolicy(){
  product.setRetirementLocked("1"); when(retirements.selectOne(any())).thenReturn(row);
  assertThat(service.get(10L,proposer)).isSameAs(row);
  assertThatThrownBy(()->service.editPolicy(10L,new RetirementPolicyReq(0,null,null,null,null,null,null),proposer)).hasMessageContaining("只读");
  verify(retirements,never()).update(isNull(),any());
 }
 @Test void onlySubmitterEditsPendingPolicy(){
  assertThatThrownBy(()->service.editPolicy(10L,new RetirementPolicyReq(0,null,null,null,null,null,null),leader)).hasMessageContaining("提交人");
  verify(retirements,never()).update(isNull(),any());
 }
 @Test void softwareCutoffMustDescribePolicy(){
  assertThatThrownBy(()->service.editPolicy(10L,new RetirementPolicyReq(0,null,null,null,null,new Date(),null),proposer)).hasMessageContaining("软件支持政策");
 }
 @Test void pendingPolicyWritesDoNotRetireProduct(){
  service.editPolicy(10L,new RetirementPolicyReq(0,new Date(),null,null,null,null,null),proposer);
  verify(retirements).update(isNull(),any()); verify(products,never()).approveRetirement(anyLong(),anyString(),any());
 }
 @Test void compareAndSetFailureDoesNotChangeProduct(){
  when(retirements.update(isNull(),any())).thenReturn(0);
  assertThatThrownBy(()->service.decide(10L,new RetirementDecisionReq(0,"APPROVE",null),leader)).hasMessageContaining("刷新");
  verify(products,never()).approveRetirement(anyLong(),anyString(),any());
 }
 @Test void concurrentRetirementLockRejectsNewSubmission(){
  when(products.isRetirementLockedForUpdate(10L)).thenReturn(true);
  assertThatThrownBy(()->service.submit(10L,new RetirementSubmitReq(0,"退市"),proposer)).hasMessageContaining("只读");
  verify(retirements,never()).insert(any(ProductRetirement.class));
 }
 @Test void viewProvidesAuthorityAndKeepsOldRejectOpinionInHistory(){
  when(retirements.selectOne(any())).thenReturn(row);
  when(auditRows.selectList(any())).thenReturn(java.util.List.of(AuditLog.builder().operatorId(3L).operatorName("负责人")
   .action("REJECT_RETIREMENT").reason("原拒绝意见").createTime(new Date()).build()));
  var view=service.view(10L,leader);
  assertThat(view.canDecide()).isTrue(); assertThat(view.canEditPolicy()).isFalse();
  assertThat(view.history().get(0).reason()).isEqualTo("原拒绝意见");
 }
 @Test void newApplicationViewLetsMemberSubmitButDoesNotInventApprover(){
  when(retirements.selectOne(any())).thenReturn(null);
  var view=service.view(10L,proposer);
  assertThat(view.canSubmit()).isTrue(); assertThat(view.canDecide()).isFalse(); assertThat(view.history()).isEmpty();
  verifyNoInteractions(auditRows);
 }

 @Test void crossTenantProductIsUnavailableEvenToAdmin(){
  product.setTenantId("other"); var admin=new IpdActor(1L,"管理员","SUPER_ADMIN",1L);
  assertThatThrownBy(()->service.submit(10L,new RetirementSubmitReq(0,"退市"),admin)).hasMessageContaining("不存在");
  assertThatThrownBy(()->service.view(10L,admin)).hasMessageContaining("不存在");
  verifyNoInteractions(retirements,audit,auditRows);
 }
 @Test void crossTenantLineCannotAuthorizeAdmin(){
  line.setTenantId("other");
  assertThatThrownBy(()->service.decide(10L,new RetirementDecisionReq(0,"APPROVE",null),leader)).hasMessageContaining("产品线");
  verify(products,never()).approveRetirement(anyLong(),anyString(),any());
 }
 @Test void crossTenantRetirementCannotBeConsumed(){
  row.setTenantId("other");
  assertThatThrownBy(()->service.decide(10L,new RetirementDecisionReq(0,"APPROVE",null),leader)).hasMessageContaining("申请不存在");
  verify(products,never()).approveRetirement(anyLong(),anyString(),any());
 }
 @Test void leaderChangedBeforeLineLockCannotApprove(){
  ProductLine current=ProductLine.builder().id(20L).status("ACTIVE").tenantId("000000").leaderPersonId(4L).build();
  when(lines.selectOne(any())).thenReturn(current);
  assertThatThrownBy(()->service.decide(10L,new RetirementDecisionReq(0,"APPROVE",null),leader)).hasMessageContaining("当前产品线负责人");
  verify(products,never()).isRetirementLockedForUpdate(anyLong());
 }
 @Test void memberExitBeforeLineLockBlocksSubmission(){
  when(members.selectCount(any())).thenReturn(1L,0L);
  assertThatThrownBy(()->service.submit(10L,new RetirementSubmitReq(0,"退市"),proposer)).hasMessageContaining("在职成员");
  verify(products,never()).isRetirementLockedForUpdate(anyLong());
 }
 @Test void retiredAdminLeaderFlagMatchesDecisionAuthority(){
  line.setLeaderPersonId(1L); when(members.selectCount(any())).thenReturn(0L);
  when(retirements.selectOne(any())).thenReturn(row); when(auditRows.selectList(any())).thenReturn(java.util.List.of());
  var admin=new IpdActor(1L,"管理员","SUPER_ADMIN",1L);
  assertThat(service.view(10L,admin).canDecide()).isFalse();
  assertThatThrownBy(()->service.decide(10L,new RetirementDecisionReq(0,"APPROVE",null),admin)).hasMessageContaining("在职成员");
 }

 @Test void cutoffEffectsEvaluatedCorrectlyBasedOnDates(){
  row.setStatus("APPROVED");
  Date now = new Date(1700000000000L);
  row.setMarketingStopAt(new Date(now.getTime() - 10000));
  row.setOrderStopAt(new Date(now.getTime() + 10000));
  row.setProductionStopAt(new Date(now.getTime() + 20000));
  row.setSpareSupportStopAt(new Date(now.getTime() + 30000));
  row.setSoftwareSupportStopAt(new Date(now.getTime() + 40000));
  var effects = service.evaluateEffects(row, now);
  assertThat(effects.marketingStopped()).isTrue();
  assertThat(effects.orderStopped()).isFalse();
  assertThat(effects.productionStopped()).isFalse();
  assertThat(effects.spareSupportStopped()).isFalse();
  assertThat(effects.softwareSupportStopped()).isFalse();
  assertThat(effects.lifecyclePhase()).isEqualTo("MARKETING_STOPPED");

  Date later = new Date(now.getTime() + 50000);
  var fullyRetired = service.evaluateEffects(row, later);
  assertThat(fullyRetired.marketingStopped()).isTrue();
  assertThat(fullyRetired.orderStopped()).isTrue();
  assertThat(fullyRetired.productionStopped()).isTrue();
  assertThat(fullyRetired.spareSupportStopped()).isTrue();
  assertThat(fullyRetired.softwareSupportStopped()).isTrue();
  assertThat(fullyRetired.lifecyclePhase()).isEqualTo("FULLY_RETIRED");
 }

}
