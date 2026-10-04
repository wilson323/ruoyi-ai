package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.*;
import org.ruoyi.ipd.dto.product.*;
import org.ruoyi.ipd.mapper.*;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.vo.RetirementView;
import java.util.List;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Date;
import java.util.Objects;

/** The existing product retirement record is the sole approval and policy path. */
@Service @RequiredArgsConstructor
public class ProductRetirementService {
 private final ProductMapper products;
 private final ProductLineMapper lines;
 private final ProductLineMemberMapper members;
 private final ProductRetirementMapper retirements;
 private final IAuditLogService audit;
 private final AuditLogMapper auditRows;
 private java.time.Clock clock=java.time.Clock.systemDefaultZone();
 /** Test/deployment clock injection preserves the existing dependency constructor. */
 @org.springframework.beans.factory.annotation.Autowired(required=false)
 public void setClock(java.time.Clock clock) {this.clock=java.util.Objects.requireNonNull(clock);}
 private static final String PENDING="PENDING_RD_LEADER";

 public ProductRetirement get(Long productId, IpdActor actor) {
  Product product=product(productId); requireReader(product,actor);
  return retirements.selectOne(new LambdaQueryWrapper<ProductRetirement>().eq(ProductRetirement::getProductId,productId).eq(ProductRetirement::getTenantId,tenant()));
 }
 public RetirementView view(Long productId,IpdActor actor) {
  Product product=product(productId); requireReader(product,actor); ProductLine line=line(product);
  ProductRetirement row=retirements.selectOne(new LambdaQueryWrapper<ProductRetirement>().eq(ProductRetirement::getProductId,productId).eq(ProductRetirement::getTenantId,tenant()));
  boolean writable=!"1".equals(product.getRetirementLocked());
  boolean owner=row!=null && Objects.equals(actor.id(),row.getProposerId());
  boolean pending=row!=null && PENDING.equals(row.getStatus());
  boolean approver=line.getLeaderPersonId()==null?"SUPER_ADMIN".equals(actor.role()):Objects.equals(actor.id(),line.getLeaderPersonId()) && isMember(line,actor);
  List<RetirementView.History> history=row==null?List.of():auditRows.selectList(new LambdaQueryWrapper<AuditLog>()
   .eq(AuditLog::getEntityType,"product_retirements").eq(AuditLog::getEntityId,row.getId()).eq(AuditLog::getTenantId,tenant()).orderByAsc(AuditLog::getSeq))
   .stream().map(item->new RetirementView.History(item.getOperatorId(),item.getOperatorName(),item.getAction(),item.getReason(),item.getCreateTime())).toList();
  Date now=Date.from(clock.instant());
  RetirementView.Effects effects=evaluateEffects(row,now);
  return new RetirementView(row,writable && (row==null || (owner && "REJECTED".equals(row.getStatus()))),
   writable && owner && pending,writable && pending && approver && !owner,history,effects);
 }
 public RetirementView.Effects evaluateEffects(ProductRetirement row, Date now) {
  if (row==null || !"APPROVED".equals(row.getStatus())) {
   return new RetirementView.Effects(false, false, false, false, false, "ACTIVE");
  }
  boolean mkt=row.getMarketingStopAt()!=null && !now.before(row.getMarketingStopAt());
  boolean ord=row.getOrderStopAt()!=null && !now.before(row.getOrderStopAt());
  boolean prd=row.getProductionStopAt()!=null && !now.before(row.getProductionStopAt());
  boolean spr=row.getSpareSupportStopAt()!=null && !now.before(row.getSpareSupportStopAt());
  boolean sfw=row.getSoftwareSupportStopAt()!=null && !now.before(row.getSoftwareSupportStopAt());
  String phase=(prd && spr && sfw)?"FULLY_RETIRED"
   :prd?"PRODUCTION_STOPPED"
   :ord?"ORDER_STOPPED"
   :mkt?"MARKETING_STOPPED"
   :"RETIREMENT_APPROVED";
  return new RetirementView.Effects(mkt, ord, prd, spr, sfw, phase);
 }
 public RetirementView.Effects evaluateEffects(Long productId) {
  ProductRetirement row=retirements.selectOne(new LambdaQueryWrapper<ProductRetirement>()
   .eq(ProductRetirement::getProductId,productId).eq(ProductRetirement::getTenantId,tenant()));
  return evaluateEffects(row,Date.from(clock.instant()));
 }
 public boolean isMarketingStopped(Long productId) {
  return evaluateEffects(productId).marketingStopped();
 }
 public boolean isOrderStopped(Long productId) {
  return evaluateEffects(productId).orderStopped();
 }
 public boolean isProductionStopped(Long productId) {
  return evaluateEffects(productId).productionStopped();
 }
 @Transactional(rollbackFor=Exception.class)
 public ProductRetirement submit(Long productId, RetirementSubmitReq req, IpdActor actor) {
  Product product=product(productId); requireReader(product,actor);
  text(req.reason(),500,"请填写退市理由");
  ProductLine lockedLine=lineForUpdate(product); requireReader(product,actor,lockedLine);
  lock(product); ProductRetirement row=retirements.findForUpdate(productId,tenant());
  if(row==null) {
   version(0,req.expectedVersion()); row=new ProductRetirement(); row.setProductId(productId);
   row.setTenantId(product.getTenantId()); row.setDelFlag("0"); row.setVersion(0);
   // The third historical readiness counter has no formally defined source. Do not claim passed.
   row.setReadinessActiveProjects(-1); row.setReadinessActiveReviews(-1);
   row.setReadinessOpenIssues(-1); row.setReadinessPassed("0");
   row.setProposerId(actor.id()); row.setProposerRole(actor.role()); row.setReason(req.reason().trim());
   row.setStatus(PENDING); row.setCreateBy(actor.id()); row.setUpdateBy(actor.id());
   if(retirements.insert(row)!=1) throw conflict("退市申请未保存，请重试");
  } else {
   verifyRecord(row,productId); version(row.getVersion(),req.expectedVersion());
   if(!Objects.equals(row.getProposerId(),actor.id())) throw forbidden("只有提交人能修改并重新提交退市申请");
   if(!"REJECTED".equals(row.getStatus())) throw conflict("已有退市申请，请查看当前申请");
   row.setReason(req.reason().trim()); row.setStatus(PENDING); row.setRejectedAt(null);
   row.setRdLeaderId(null); row.setRdDecision(null); row.setRdDecidedAt(null); row.setRdOpinion(null);
   // Explicit NULL assignments are required: MyBatis entity updates omit NULL by default.
   update(row,actor,new LambdaUpdateWrapper<ProductRetirement>()
    .set(ProductRetirement::getReason,row.getReason()).set(ProductRetirement::getStatus,PENDING)
    .set(ProductRetirement::getRejectedAt,null).set(ProductRetirement::getRdLeaderId,null)
    .set(ProductRetirement::getRdDecision,null).set(ProductRetirement::getRdDecidedAt,null)
    .set(ProductRetirement::getRdOpinion,null));
  }
  audit.append(actor,"SUBMIT_RETIREMENT","product_retirements",row.getId(),row.getReason()); return row;
 }
 @Transactional(rollbackFor=Exception.class)
 public ProductRetirement editPolicy(Long productId,RetirementPolicyReq req,IpdActor actor) {
  Product product=product(productId); requireReader(product,actor);
  ProductLine lockedLine=lineForUpdate(product); requireReader(product,actor,lockedLine); lock(product);
  ProductRetirement row=pending(productId,req.expectedVersion());
  if(!Objects.equals(row.getProposerId(),actor.id())) throw forbidden("只有提交人能修改待审核的退市政策");
  if(req.softwareSupportStopAt()!=null) text(req.softwareSupportPolicy(),2000,"请填写软件支持政策");
  if(req.softwareSupportPolicy()!=null && req.softwareSupportPolicy().length()>2000) throw conflict("软件支持政策不能超过2000字");
  update(row,actor,new LambdaUpdateWrapper<ProductRetirement>()
   .set(ProductRetirement::getMarketingStopAt,req.marketingStopAt()).set(ProductRetirement::getOrderStopAt,req.orderStopAt())
   .set(ProductRetirement::getProductionStopAt,req.productionStopAt()).set(ProductRetirement::getSpareSupportStopAt,req.spareSupportStopAt())
   .set(ProductRetirement::getSoftwareSupportStopAt,req.softwareSupportStopAt()).set(ProductRetirement::getSoftwareSupportPolicy,req.softwareSupportPolicy()));
  audit.append(actor,"EDIT_RETIREMENT_POLICY","product_retirements",row.getId(),"更新待审核退市政策");
  return retirementById(row.getId());
 }
 @Transactional(rollbackFor=Exception.class)
 public ProductRetirement decide(Long productId,RetirementDecisionReq req,IpdActor actor) {
  Product product=product(productId); ProductLine line=line(product);
  assertApprover(line,actor);
  line=lineForUpdate(product); assertApprover(line,actor);
  lock(product); ProductRetirement row=pending(productId,req.expectedVersion());
  if(Objects.equals(row.getProposerId(),actor.id())) throw forbidden("提交人不能批准或驳回自己的退市申请");
  if(!"APPROVE".equals(req.decision()) && !"REJECT".equals(req.decision())) throw conflict("请选择批准或驳回");
  if("REJECT".equals(req.decision())) text(req.opinion(),500,"驳回时请填写意见");
  if(req.opinion()!=null && req.opinion().length()>500) throw conflict("审批意见不能超过500字");
  Date now=Date.from(clock.instant()); boolean approved="APPROVE".equals(req.decision());
  update(row,actor,new LambdaUpdateWrapper<ProductRetirement>().set(ProductRetirement::getStatus,approved?"APPROVED":"REJECTED")
   .set(ProductRetirement::getRdLeaderId,actor.id()).set(ProductRetirement::getRdDecision,req.decision())
   .set(ProductRetirement::getRdDecidedAt,now).set(ProductRetirement::getRdOpinion,req.opinion())
   .set(ProductRetirement::getApprovedAt,approved?now:null).set(ProductRetirement::getRejectedAt,approved?null:now));
  if(approved && products.approveRetirement(productId,tenant(),now)!=1) throw conflict("产品状态已变化，请重新查看");
  audit.append(actor,approved?"APPROVE_RETIREMENT":"REJECT_RETIREMENT","product_retirements",row.getId(),req.opinion());
  return retirementById(row.getId());
 }
 private ProductRetirement pending(Long id,Integer expected) {
  ProductRetirement row=retirements.findForUpdate(id,tenant()); if(row==null) throw conflict("请先提交退市申请");
  verifyRecord(row,id); version(row.getVersion(),expected); if(!PENDING.equals(row.getStatus())) throw conflict("退市申请已处理，请刷新查看"); return row;
 }
 private void update(ProductRetirement row,IpdActor actor,LambdaUpdateWrapper<ProductRetirement> patch) {
  int old=row.getVersion(); patch.eq(ProductRetirement::getId,row.getId()).eq(ProductRetirement::getTenantId,tenant()).eq(ProductRetirement::getVersion,old)
   .set(ProductRetirement::getVersion,old+1).set(ProductRetirement::getUpdateBy,actor.id());
  if(retirements.update(null,patch)!=1) throw conflict("申请已被修改，请刷新后重试"); row.setVersion(old+1);
 }
 private Product product(Long id) {Product row=products.selectById(id); if(row==null || !Objects.equals(row.getTenantId(),tenant())) throw conflict("产品不存在"); return row;}
 private ProductLine line(Product product) {
  ProductLine row=product.getProductLineId()==null?null:lines.selectById(product.getProductLineId());
  if(row==null || !Objects.equals(row.getTenantId(),tenant()) || !"ACTIVE".equals(row.getStatus())) throw conflict("产品没有可用的产品线"); return row;
 }
 private void requireReader(Product product,IpdActor actor) {
  requireReader(product,actor,line(product));
 }
 private void requireReader(Product product,IpdActor actor,ProductLine line) {
  if(actor==null || actor.id()==null) throw forbidden("请先登录");
  if(!"SUPER_ADMIN".equals(actor.role())) requireMember(line,actor);
 }
 private void requireMember(ProductLine line,IpdActor actor) {
  if(!isMember(line,actor)) throw forbidden("只有该产品线在职成员能访问退市申请");
 }
 private boolean isMember(ProductLine line,IpdActor actor) {
  Long count=members.selectCount(new LambdaQueryWrapper<ProductLineMember>().eq(ProductLineMember::getProductLineId,line.getId())
   .eq(ProductLineMember::getPersonId,actor.id()).eq(ProductLineMember::getStatus,"ACTIVE").eq(ProductLineMember::getTenantId,tenant()));
  return count!=null && count>0;
 }
 private void verifyRecord(ProductRetirement row,Long productId) {
  if(!Objects.equals(row.getTenantId(),tenant()) || !Objects.equals(row.getProductId(),productId)) throw conflict("退市申请不存在");
 }
 private ProductRetirement retirementById(Long id) {
  return retirements.selectOne(new LambdaQueryWrapper<ProductRetirement>().eq(ProductRetirement::getId,id).eq(ProductRetirement::getTenantId,tenant()));
 }
 private ProductLine lineForUpdate(Product product) {
  ProductLine row=lines.selectOne(new LambdaQueryWrapper<ProductLine>().eq(ProductLine::getId,product.getProductLineId())
   .eq(ProductLine::getTenantId,tenant()).eq(ProductLine::getStatus,"ACTIVE").last("FOR UPDATE"));
  if(row==null || !Objects.equals(row.getTenantId(),tenant())) throw conflict("产品线空间不存在"); return row;
 }
 private void assertApprover(ProductLine line,IpdActor actor) {
  if(actor==null || actor.id()==null) throw forbidden("请先登录");
  if(line.getLeaderPersonId()==null) {
   if(!"SUPER_ADMIN".equals(actor.role())) throw forbidden("没有产品线负责人时只有系统管理员能审批退市");
  } else {
   if(!line.getLeaderPersonId().equals(actor.id())) throw forbidden("只有当前产品线负责人能审批退市");
   requireMember(line,actor);
  }
 }
 private static String tenant() {
  String value=LoginHelper.getTenantId(); return value==null || value.isBlank()?"000000":value;
 }
 private void lock(Product product) {
  if("1".equals(product.getRetirementLocked()) || products.isRetirementLockedForUpdate(product.getId())) throw conflict("产品已退市，历史记录只读");
  Product current=product(product.getId());
  if(!Objects.equals(current.getProductLineId(),product.getProductLineId())) throw conflict("产品归属已变化，请刷新后重试");
 }
 private static void version(Integer actual,Integer expected) {if(expected==null || !Objects.equals(actual,expected)) throw conflict("申请已变化，请刷新后重试");}
 private static void text(String value,int max,String message) {if(value==null || value.isBlank() || value.length()>max) throw conflict(message);}
 private static IpdBusinessException conflict(String message){return new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,message);}
 private static IpdBusinessException forbidden(String message){return new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN,message);}
}
