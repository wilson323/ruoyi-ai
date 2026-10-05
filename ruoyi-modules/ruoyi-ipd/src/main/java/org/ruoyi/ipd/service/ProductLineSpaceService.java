package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.ProductLineMember;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductLineMemberMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 产品线是稳定的团队大工作空间；成员资格不授予项目级执行权限。 */
@Service
@RequiredArgsConstructor
public class ProductLineSpaceService {
    private final ProductLineMapper lineMapper;
    private final ProductLineMemberMapper memberMapper;
    private final ProductMapper productMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final PersonMapper personMapper;
    private final IAuditLogService auditLogService;
    private Clock clock = Clock.systemDefaultZone();
    private RequirementMapper requirementMapper;

    /** 生产环境注入后，空间才能列出已绑定该产品线的游客需求。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRequirementMapper(RequirementMapper requirementMapper) {
        this.requirementMapper = requirementMapper;
    }

    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductLine create(String code, String name, IpdActor actor) {
        requireAdmin(actor);
        if (code == null || !code.trim().matches("[A-Za-z0-9_-]{1,64}")
                || name == null || name.isBlank() || name.trim().length() > 128) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "产品线编码或名称非法");
        }
        ProductLine line = ProductLine.builder().lineCode(code.trim()).lineName(name.trim())
            .status("ACTIVE").tenantId(tenant()).delFlag("0").build();
        line.setCreateBy(actor.id());
        line.setCreateTime(Date.from(clock.instant()));
        try {
            lineMapper.insert(line);
        } catch (DuplicateKeyException ex) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产品线编码已存在");
        }
        auditLogService.append(actor, "PRODUCT_LINE_CREATE", "product_lines", line.getId(), line.getLineCode());
        return line;
    }

    public List<ProductLine> visibleLines(IpdActor actor) {
        IpdIdorGuard.requireAuthenticated(actor);
        if ("SUPER_ADMIN".equals(actor.role())) {
            return lineMapper.selectList(new LambdaQueryWrapper<ProductLine>()
                .eq(ProductLine::getTenantId, tenant()).eq(ProductLine::getStatus, "ACTIVE")
                .orderByAsc(ProductLine::getLineName));
        }
        List<Long> lineIds = memberMapper.selectList(new LambdaQueryWrapper<ProductLineMember>()
            .eq(ProductLineMember::getPersonId, actor.id())
            .eq(ProductLineMember::getStatus, "ACTIVE")
            .eq(ProductLineMember::getTenantId, tenant()))
            .stream().map(ProductLineMember::getProductLineId).toList();
        if (lineIds.isEmpty()) return List.of();
        return lineMapper.selectList(new LambdaQueryWrapper<ProductLine>()
            .in(ProductLine::getId, lineIds).eq(ProductLine::getTenantId, tenant())
            .eq(ProductLine::getStatus, "ACTIVE").orderByAsc(ProductLine::getLineName));
    }

    /** 申请入口只展示本租户活动空间，不暴露成员或项目资料。 */
    public List<ProductLine> discoverableLines(IpdActor actor) {
        IpdIdorGuard.requireAuthenticated(actor);
        return lineMapper.selectList(new LambdaQueryWrapper<ProductLine>()
            .eq(ProductLine::getTenantId, tenant()).eq(ProductLine::getStatus, "ACTIVE")
            .orderByAsc(ProductLine::getLineName));
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductLine rename(Long lineId, String name, IpdActor actor) {
        requireAdmin(actor);
        ProductLine line = requireActiveLineForUpdate(lineId);
        if (name == null || name.isBlank() || name.trim().length() > 128) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "产品线名称非法");
        }
        String nextName = name.trim();
        if (nextName.equals(line.getLineName())) return line;
        int updated = lineMapper.update(null, new LambdaUpdateWrapper<ProductLine>()
            .eq(ProductLine::getId, lineId).eq(ProductLine::getTenantId, tenant())
            .eq(ProductLine::getStatus, "ACTIVE").eq(ProductLine::getLineName, line.getLineName())
            .set(ProductLine::getLineName, nextName).set(ProductLine::getUpdateBy, actor.id()));
        if (updated != 1) throw conflict();
        auditLogService.append(actor, "PRODUCT_LINE_RENAME", "product_lines", lineId,
            "old=" + line.getLineName() + ",new=" + nextName);
        line.setLineName(nextName);
        return line;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductLine deactivate(Long lineId, IpdActor actor) {
        requireAdmin(actor);
        ProductLine line = requireActiveLineForUpdate(lineId);
        // INACTIVE 产品仍可重新转为在售；停用前必须先解除所有产品归属。
        if (productMapper.selectCount(new LambdaQueryWrapper<Product>()
                .eq(Product::getProductLineId, lineId).eq(Product::getTenantId, tenant())) > 0
            || memberMapper.selectCount(new LambdaQueryWrapper<ProductLineMember>()
                .eq(ProductLineMember::getProductLineId, lineId).eq(ProductLineMember::getTenantId, tenant())
                .eq(ProductLineMember::getStatus, "PENDING")) > 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "空间仍有关联产品或待审批申请");
        }
        int updated = lineMapper.update(null, new LambdaUpdateWrapper<ProductLine>()
            .eq(ProductLine::getId, lineId).eq(ProductLine::getTenantId, tenant())
            .eq(ProductLine::getStatus, "ACTIVE")
            .set(ProductLine::getStatus, "INACTIVE").set(ProductLine::getUpdateBy, actor.id()));
        if (updated != 1) throw conflict();
        auditLogService.append(actor, "PRODUCT_LINE_DEACTIVATE", "product_lines", lineId, line.getLineCode());
        line.setStatus("INACTIVE");
        return line;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductLineMember apply(Long lineId, IpdActor actor) {
        IpdIdorGuard.requireAuthenticated(actor);
        ProductLine line = requireActiveLineForUpdate(lineId);
        requireActivePerson(actor.id());
        ProductLineMember member = findMember(line.getId(), actor.id());
        if (member != null && ("PENDING".equals(member.getStatus()) || "ACTIVE".equals(member.getStatus()))) {
            return member;
        }
        if (member == null) {
            member = ProductLineMember.builder().productLineId(line.getId()).personId(actor.id())
                .status("PENDING").tenantId(tenant()).delFlag("0").build();
            member.setCreateBy(actor.id());
            member.setCreateTime(Date.from(clock.instant()));
            try {
                memberMapper.insert(member);
            } catch (DuplicateKeyException ex) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "加入申请已存在，请刷新");
            }
        } else {
            int updated = memberMapper.update(null, new LambdaUpdateWrapper<ProductLineMember>()
                .eq(ProductLineMember::getId, member.getId())
                .in(ProductLineMember::getStatus, "REJECTED", "EXITED")
                .set(ProductLineMember::getStatus, "PENDING")
                .set(ProductLineMember::getReviewedBy, null)
                .set(ProductLineMember::getReviewedAt, null)
                .set(ProductLineMember::getExitedAt, null)
                .set(ProductLineMember::getUpdateBy, actor.id()));
            if (updated != 1) throw conflict();
            member.setStatus("PENDING");
        }
        auditLogService.append(actor, "PRODUCT_LINE_JOIN_APPLY", "product_line_members", member.getId(), "line=" + lineId);
        return member;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductLineMember leave(Long lineId, IpdActor actor) {
        IpdIdorGuard.requireAuthenticated(actor);
        ProductLine line = requireActiveLineForUpdate(lineId);
        ProductLineMember member = findMember(lineId, actor.id());
        if (member == null || !"ACTIVE".equals(member.getStatus())) throw conflict();
        if (actor.id().equals(line.getLeaderPersonId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "请先由管理员改派产品线组长");
        }
        Date now = Date.from(clock.instant());
        int updated = memberMapper.update(null, new LambdaUpdateWrapper<ProductLineMember>()
            .eq(ProductLineMember::getId, member.getId()).eq(ProductLineMember::getTenantId, tenant())
            .eq(ProductLineMember::getStatus, "ACTIVE")
            .set(ProductLineMember::getStatus, "EXITED")
            .set(ProductLineMember::getExitedAt, now).set(ProductLineMember::getUpdateBy, actor.id()));
        if (updated != 1) throw conflict();
        auditLogService.append(actor, "PRODUCT_LINE_MEMBER_LEAVE", "product_line_members", member.getId(),
            "line=" + lineId);
        member.setStatus("EXITED");
        member.setExitedAt(now);
        return member;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductLineMember removeMember(Long lineId, Long personId, IpdActor actor) {
        requireAdmin(actor);
        ProductLine line = requireActiveLineForUpdate(lineId);
        ProductLineMember member = findMember(lineId, personId);
        if (member == null || !"ACTIVE".equals(member.getStatus())) throw conflict();
        if (personId.equals(line.getLeaderPersonId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "请先改派产品线组长");
        }
        Date now = Date.from(clock.instant());
        int updated = memberMapper.update(null, new LambdaUpdateWrapper<ProductLineMember>()
            .eq(ProductLineMember::getId, member.getId()).eq(ProductLineMember::getTenantId, tenant())
            .eq(ProductLineMember::getStatus, "ACTIVE")
            .set(ProductLineMember::getStatus, "EXITED")
            .set(ProductLineMember::getExitedAt, now).set(ProductLineMember::getUpdateBy, actor.id()));
        if (updated != 1) throw conflict();
        auditLogService.append(actor, "PRODUCT_LINE_MEMBER_REMOVE", "product_line_members", member.getId(),
            "line=" + lineId + ",person=" + personId);
        member.setStatus("EXITED");
        member.setExitedAt(now);
        return member;
    }

    public List<ProductLineMember> pendingApplications(Long lineId, IpdActor actor) {
        ProductLine line = requireActiveLine(lineId);
        requireReviewer(actor, line);
        return memberMapper.selectList(new LambdaQueryWrapper<ProductLineMember>()
            .eq(ProductLineMember::getProductLineId, lineId)
            .eq(ProductLineMember::getTenantId, tenant())
            .eq(ProductLineMember::getStatus, "PENDING")
            .orderByAsc(ProductLineMember::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductLineMember review(Long lineId, Long personId, boolean approve, IpdActor actor) {
        ProductLine line = requireActiveLineForUpdate(lineId);
        requireReviewer(actor, line);
        ProductLineMember member = findMember(lineId, personId);
        if (member == null || !"PENDING".equals(member.getStatus())) throw conflict();
        if (approve) {
            Person person = personMapper.selectById(personId);
            if (person == null || !Objects.equals(person.getTenantId(), tenant())
                    || !"ACTIVE".equals(person.getAccountStatus())
                    || !"ACTIVE".equals(person.getEmploymentStatus())) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "申请人已非在职人员");
            }
        }
        Date now = Date.from(clock.instant());
        String status = approve ? "ACTIVE" : "REJECTED";
        LambdaUpdateWrapper<ProductLineMember> update = new LambdaUpdateWrapper<ProductLineMember>()
            .eq(ProductLineMember::getId, member.getId())
            .eq(ProductLineMember::getStatus, "PENDING")
            .set(ProductLineMember::getStatus, status)
            .set(ProductLineMember::getReviewedBy, actor.id())
            .set(ProductLineMember::getReviewedAt, now)
            .set(ProductLineMember::getUpdateBy, actor.id());
        if (approve) update.set(ProductLineMember::getJoinedAt, now);
        if (memberMapper.update(null, update) != 1) throw conflict();
        auditLogService.append(actor, approve ? "PRODUCT_LINE_JOIN_APPROVE" : "PRODUCT_LINE_JOIN_REJECT",
            "product_line_members", member.getId(), "line=" + lineId + ",person=" + personId);
        member.setStatus(status);
        member.setReviewedBy(actor.id());
        member.setReviewedAt(now);
        if (approve) member.setJoinedAt(now);
        return member;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductLine appointLeader(Long lineId, Long personId, IpdActor actor) {
        requireAdmin(actor);
        ProductLine line = requireActiveLineForUpdate(lineId);
        if (personId == null) throw invalidId();
        Person person = personMapper.selectById(personId);
        ProductLineMember member = findMember(lineId, personId);
        if (person == null || !Objects.equals(person.getTenantId(), tenant())
                || !"ACTIVE".equals(person.getAccountStatus())
                || !"ACTIVE".equals(person.getEmploymentStatus())
                || member == null || !"ACTIVE".equals(member.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "组长必须是该空间的在职成员");
        }
        if (personId.equals(line.getLeaderPersonId())) return line;
        int updated = lineMapper.update(null, new LambdaUpdateWrapper<ProductLine>()
            .eq(ProductLine::getId, lineId).eq(ProductLine::getTenantId, tenant())
            .eq(ProductLine::getStatus, "ACTIVE")
            .set(ProductLine::getLeaderPersonId, personId)
            .set(ProductLine::getUpdateBy, actor.id()));
        if (updated != 1) throw conflict();
        auditLogService.append(actor, "PRODUCT_LINE_LEADER_APPOINT", "product_lines", lineId, "person=" + personId);
        line.setLeaderPersonId(personId);
        return line;
    }

    @Transactional(rollbackFor = Exception.class)
    public Product assignProduct(Long lineId, Long productId, IpdActor actor) {
        requireAdmin(actor);
        requireActiveLineForUpdate(lineId);
        if (productId == null) throw invalidId();
        Product product = productMapper.selectById(productId);
        if (product == null || !Objects.equals(product.getTenantId(), tenant())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "产品不存在");
        }
        if (product.getProductLineId() != null && !lineId.equals(product.getProductLineId())) throw conflict();
        if (lineId.equals(product.getProductLineId())) return product;
        requireProductWritable(product);
        int updated = productMapper.update(null, new LambdaUpdateWrapper<Product>()
            .eq(Product::getId, productId).isNull(Product::getProductLineId)
            .eq(Product::getTenantId, tenant())
            .set(Product::getProductLineId, lineId)
            .set(Product::getUpdateBy, actor.id()));
        if (updated != 1) throw conflict();
        auditLogService.append(actor, "PRODUCT_LINE_PRODUCT_ASSIGN", "products", productId, "line=" + lineId);
        product.setProductLineId(lineId);
        return product;
    }

    /** 解绑只改产品线。产品与项目的挂接不变：一个产品可有多个项目，project_id 仍是首个项目指针。 */
    @Transactional(rollbackFor = Exception.class)
    public Product unassignProduct(Long lineId, Long productId, IpdActor actor) {
        requireAdmin(actor);
        requireActiveLineForUpdate(lineId);
        if (productId == null) throw invalidId();
        Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
            .eq(Product::getId, productId).eq(Product::getTenantId, tenant()).last("FOR UPDATE"));
        if (product == null || !lineId.equals(product.getProductLineId())) throw conflict();
        requireProductWritable(product);
        if (!Product.ST_INACTIVE.equals(product.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "仅停用产品可解除产品线归属");
        }
        if (product.getProjectId() != null) {
            Project linked = projectMapper.selectById(product.getProjectId());
            if (linked == null || !Objects.equals(linked.getTenantId(), tenant())
                    || !productId.equals(linked.getProductId())
                    || !"ARCHIVED".equals(linked.getStatus())) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产品仍有关联的活动项目");
            }
        }
        if (projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                .eq(Project::getProductId, productId).eq(Project::getTenantId, tenant())
                .and(query -> query.isNull(Project::getStatus)
                    .or().ne(Project::getStatus, "ARCHIVED"))) > 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产品仍有关联的活动项目");
        }
        int updated = productMapper.update(null, new LambdaUpdateWrapper<Product>()
            .eq(Product::getId, productId).eq(Product::getTenantId, tenant())
            .eq(Product::getProductLineId, lineId).eq(Product::getStatus, Product.ST_INACTIVE)
            .set(Product::getProductLineId, null).set(Product::getUpdateBy, actor.id()));
        if (updated != 1) throw conflict();
        auditLogService.append(actor, "PRODUCT_LINE_PRODUCT_UNASSIGN", "products", productId,
            "line=" + lineId);
        product.setProductLineId(null);
        return product;
    }

    public List<Product> products(Long lineId, IpdActor actor) {
        ProductLine line = requireActiveLine(lineId);
        requireMemberOrAdmin(actor, line);
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
            .eq(Product::getProductLineId, lineId)
            .eq(Product::getTenantId, tenant())
            .orderByAsc(Product::getProductName));
    }

    /**
     * 负责人和超管看本线全部项目。其他成员只看自己加入的项目，以及自己创建、尚未开工的项目。
     */
    public List<Project> projects(Long lineId, IpdActor actor) {
        ProductLine line = requireActiveLine(lineId);
        requireMemberOrAdmin(actor, line);
        java.util.LinkedHashMap<Long, Project> merged = new java.util.LinkedHashMap<>();
        List<Long> directIds = projectMapper.findIdsByProductLine(lineId);
        if (directIds != null && !directIds.isEmpty()) {
            for (Project project : projectMapper.selectByIds(directIds)) {
                if (project.getId() != null) merged.put(project.getId(), project);
            }
        }
        List<Long> productIds = productMapper.selectList(new LambdaQueryWrapper<Product>()
            .eq(Product::getProductLineId, lineId).eq(Product::getTenantId, tenant()))
            .stream().map(Product::getId).toList();
        if (!productIds.isEmpty()) {
            for (Project project : projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .in(Project::getProductId, productIds).eq(Project::getTenantId, tenant()))) {
                if (project.getId() != null) merged.put(project.getId(), project);
            }
        }
        List<Project> projects = new java.util.ArrayList<>(merged.values());
        boolean leader = actor.id() != null && actor.id().equals(line.getLeaderPersonId());
        if ("SUPER_ADMIN".equals(actor.role()) || leader || projects.isEmpty()) return projects;
        Set<Long> memberProjectIds = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getPersonId, actor.id())
            .in(ProjectMember::getProjectId, projects.stream().map(Project::getId).toList())
            .isNull(ProjectMember::getExitDate))
            .stream().map(ProjectMember::getProjectId).collect(Collectors.toSet());
        return projects.stream().filter(project -> memberProjectIds.contains(project.getId())
            || (unstarted(project) && actor.id() != null && actor.id().equals(project.getCreateBy()))).toList();
    }

    private boolean unstarted(Project project) {
        return "PENDING_START".equals(project.getStatus()) || "START_REJECTED".equals(project.getStatus());
    }

    /** 只列出已经写上本产品线的需求，不猜测未绑定产品的需求归属。 */
    public List<Requirement> demands(Long lineId, IpdActor actor) {
        ProductLine line = requireActiveLine(lineId);
        requireMemberOrAdmin(actor, line);
        if (requirementMapper == null) return List.of();
        LambdaQueryWrapper<Requirement> query = new LambdaQueryWrapper<Requirement>()
            .apply("tenant_id = {0}", tenant()).orderByDesc(Requirement::getId)
            .last("LIMIT 200");
        if ("unspecified".equals(line.getLineCode())) {
            query.and(wrapper -> wrapper.eq(Requirement::getProductLineId, lineId)
                .or().isNull(Requirement::getProductLineId));
        } else {
            query.eq(Requirement::getProductLineId, lineId);
        }
        return requirementMapper.selectList(query);
    }

    /** 分拣回读复用空间成员边界，重试仅负责人或管理员可发起。 */
    public Requirement requireTriageDemand(Long lineId, Long demandId, IpdActor actor, boolean retry) {
        ProductLine line = requireActiveLine(lineId);
        requireMemberOrAdmin(actor, line);
        if (retry && !"SUPER_ADMIN".equals(actor.role()) && !Objects.equals(actor.id(), line.getLeaderPersonId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "仅产品线负责人可重试分拣");
        }
        Requirement demand = requirementMapper.selectOne(new LambdaQueryWrapper<Requirement>()
            .eq(Requirement::getId, demandId).apply("tenant_id = {0}", tenant()));
        if (demand == null || !(Objects.equals(lineId, demand.getProductLineId())
            || ("unspecified".equals(line.getLineCode()) && demand.getProductLineId() == null))) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "需求不在当前产品线空间");
        }
        return demand;
    }

    private void requireProductWritable(Product product) {
        if ("1".equals(product.getRetirementLocked())
            || productMapper.isRetirementLockedForUpdate(product.getId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "该产品已退市并只读，不能调整产品线归属");
        }
    }

    private ProductLine requireActiveLine(Long lineId) {
        if (lineId == null) throw invalidId();
        ProductLine line = lineMapper.selectById(lineId);
        if (line == null || !Objects.equals(line.getTenantId(), tenant()) || !"ACTIVE".equals(line.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "产品线空间不存在");
        }
        return line;
    }

    /** 串行化产品归属、成员申请和停用，避免计数后插入造成停用空间仍有活动事项。 */
    private ProductLine requireActiveLineForUpdate(Long lineId) {
        if (lineId == null) throw invalidId();
        ProductLine line = lineMapper.selectOne(new LambdaQueryWrapper<ProductLine>()
            .eq(ProductLine::getId, lineId).eq(ProductLine::getTenantId, tenant())
            .eq(ProductLine::getStatus, "ACTIVE").last("FOR UPDATE"));
        if (line == null) throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "产品线空间不存在");
        return line;
    }

    private ProductLineMember findMember(Long lineId, Long personId) {
        if (personId == null) throw invalidId();
        return memberMapper.selectOne(new LambdaQueryWrapper<ProductLineMember>()
            .eq(ProductLineMember::getProductLineId, lineId)
            .eq(ProductLineMember::getPersonId, personId)
            .eq(ProductLineMember::getTenantId, tenant()));
    }

    private void requireActivePerson(Long personId) {
        Person person = personMapper.selectById(personId);
        if (person == null || !Objects.equals(person.getTenantId(), tenant())
                || !"ACTIVE".equals(person.getAccountStatus())
                || !"ACTIVE".equals(person.getEmploymentStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "仅本租户在职人员可申请加入空间");
        }
    }

    private void requireMemberOrAdmin(IpdActor actor, ProductLine line) {
        IpdIdorGuard.requireAuthenticated(actor);
        if ("SUPER_ADMIN".equals(actor.role())) return;
        ProductLineMember member = findMember(line.getId(), actor.id());
        if (member == null || !"ACTIVE".equals(member.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权访问产品线空间");
        }
    }

    private void requireReviewer(IpdActor actor, ProductLine line) {
        IpdIdorGuard.requireAuthenticated(actor);
        if ("SUPER_ADMIN".equals(actor.role())) return;
        if (!actor.id().equals(line.getLeaderPersonId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权审批该空间申请");
        }
        requireMemberOrAdmin(actor, line);
    }

    private static void requireAdmin(IpdActor actor) {
        IpdIdorGuard.requireAuthenticated(actor);
        if (!"SUPER_ADMIN".equals(actor.role())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "仅管理员可维护产品线归属");
        }
    }

    private static String tenant() {
        String value = LoginHelper.getTenantId();
        return value == null || value.isBlank() ? "000000" : value;
    }

    private static IpdBusinessException invalidId() {
        return new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "ID 不能为空");
    }

    private static IpdBusinessException conflict() {
        return new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "空间成员或产品归属状态已变化");
    }
}
