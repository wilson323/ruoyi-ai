package org.ruoyi.service.knowledge.impl;

import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 知识库检索访问门 B0 默认实现（读面 owned/share + 管理面仅 owned）。
 * <p>
 * 读面 {@link #checkRetrievalAccess(Long, Long)}：kid 非空 && 库存在 &&
 * （库归属当前用户 || share=1 公开库）。B0 判据仅使用现有字段。
 * 管理面 {@link #assertManageable(Long, Long)}：kid 非空 && 库存在 && 超级管理员豁免或库归属当前用户。
 * share=1 公开不授予写权（最佳实践 §8.1：share 从未构成安全边界；edit 翻 share 是
 * B0 审计破坏面 C 的 Gate 击穿提权链，管理面必须与 share 解耦）。
 * <p>
 * 两个判据的变体分工：单参供 HTTP 线程（内部读会话）；双参供非 HTTP 线程显式传身份
 * （调用方负责身份已验，如 ws 握手验 token 后放入 session attributes）。
 * <p>
 * admin 豁免依据：{@link LoginHelper#isSuperAdmin(Long)} 为 SUPER_ADMIN_ID 等值判定
 * （ruoyi-system 域 SysPermissionServiceImpl / SysUserServiceImpl 等全量同款惯例），
 * 纯常量比较、不依赖会话上下文，双参变体语义一致。普通角色（含持 system:info:edit
 * 等权限码者）不豁免——B0 审计 2.3(d)：现网 3 个普通用户持全族权限码，若按权限码豁免
 * 则提权链原样复活。现网唯一库 owner=user 1（即 superadmin 本人），豁免对现网零行为差异。
 * <p>
 * B1/B2 接入 sensitivity/scope_type 列后，由 IPD 侧提供更强实现替换本默认实现：
 * 接口定义在 ruoyi-chat、依赖方向 ruoyi-ipd → ruoyi-chat 已验证，
 * 未来 ipd 模块实现 {@link KnowledgeAccessGate} 并以更高优先级注入即可覆盖本 Bean。
 *
 * @author ruoyi
 * @date 2026-09-28
 */
@Component
public class UserIdShareKnowledgeAccessGate implements KnowledgeAccessGate {

    private final IKnowledgeInfoService knowledgeInfoService;

    /**
     * 手写构造器（不沿用 @RequiredArgsConstructor）：@Lazy 打破与
     * {@code KnowledgeInfoServiceImpl} 的构造循环——Info 侧为收敛管理面口（edit/remove）
     * 需注入本 Gate，本实现又依赖 IKnowledgeInfoService 查库归属判 owned。
     * Spring 注入 lazy 代理，首次实际调用 queryById 时才解析目标 Bean；
     * 单测直接 new 本类不受影响（@Lazy 仅在容器内生效）。
     */
    public UserIdShareKnowledgeAccessGate(@Lazy IKnowledgeInfoService knowledgeInfoService) {
        this.knowledgeInfoService = knowledgeInfoService;
    }

    /**
     * HTTP 线程变体：从 Sa-Token 会话取身份后委托双参判据。
     */
    @Override
    public void checkRetrievalAccess(Long kid) {
        // 未登录或会话上下文不可用（LoginHelper.getUserId() 返回 null）一律拒绝，且不触库
        checkRetrievalAccess(kid, LoginHelper.getUserId());
    }

    /**
     * 显式身份变体：非 HTTP 线程（如 ws 消息线程）传入握手期已校验的 userId。
     * 判据与单参变体完全同源：kid 非空 && userId 非空 && 库存在 &&（归属 || share=1）。
     */
    @Override
    public void checkRetrievalAccess(Long kid, Long userId) {
        if (kid == null) {
            throw new ServiceException("知识库ID为空，无权访问该知识库");
        }
        if (userId == null) {
            throw new ServiceException("未登录或会话已失效，无权访问该知识库 kid=" + kid);
        }
        KnowledgeInfoVo knowledgeInfo = knowledgeInfoService.queryById(kid);
        if (knowledgeInfo == null) {
            throw new ServiceException("知识库不存在，无权访问该知识库 kid=" + kid);
        }
        boolean owned = knowledgeInfo.getUserId() != null && knowledgeInfo.getUserId().equals(userId);
        boolean shared = Long.valueOf(1L).equals(knowledgeInfo.getShare());
        if (!owned && !shared) {
            throw new ServiceException("无权访问该知识库 kid=" + kid);
        }
    }

    /**
     * HTTP 线程管理面变体：从 Sa-Token 会话取身份后委托双参判据。
     */
    @Override
    public void assertManageable(Long kid) {
        // 未登录或会话上下文不可用一律拒绝，且不触库
        assertManageable(kid, LoginHelper.getUserId());
    }

    /**
     * 显式身份管理面变体：仅 owned 放行（share=1 不参与），超级管理员豁免。
     * 库存在性先行——豁免不吞「知识库不存在」错误（admin 对不存在 kid 也得到明确拒绝，
     * 与 checkRetrievalAccess 的存在性语义保持一致）。
     */
    @Override
    public void assertManageable(Long kid, Long userId) {
        if (kid == null) {
            throw new ServiceException("知识库ID为空，无权管理该知识库");
        }
        if (userId == null) {
            throw new ServiceException("未登录或会话已失效，无权管理该知识库 kid=" + kid);
        }
        KnowledgeInfoVo knowledgeInfo = knowledgeInfoService.queryById(kid);
        if (knowledgeInfo == null) {
            throw new ServiceException("知识库不存在，无权管理该知识库 kid=" + kid);
        }
        if (LoginHelper.isSuperAdmin(userId)) {
            return;
        }
        boolean owned = knowledgeInfo.getUserId() != null && knowledgeInfo.getUserId().equals(userId);
        if (!owned) {
            throw new ServiceException("仅库归属人可管理该知识库 kid=" + kid);
        }
    }

    /**
     * B2 检索接线默认实现：chat 侧无「角色→敏感级上限」权威表（§8.2 纪律：
     * 上限判定唯一权威源在 ruoyi-ipd），恒返回 fail-closed 最严档（PUBLIC）。
     * <p>
     * 单模块部署（无 ruoyi-ipd）时检索过滤若启用，所有身份都按 PUBLIC 闸门
     * ——只收不放；主部署（ruoyi-admin 聚合 ipd）由
     * {@code org.ruoyi.ipd.security.IpdKnowledgeAccessGate} 以 @Primary 覆盖本 Bean。
     */
    @Override
    public org.ruoyi.service.knowledge.RetrievalAccessProfile retrievalAccessProfile() {
        return org.ruoyi.service.knowledge.RetrievalAccessProfile.FAIL_CLOSED_PUBLIC;
    }

    /**
     * 显式身份变体同判：sys_user id 无 person 映射可解析（persons 无关联列、
     * id 空间不相交），一律 fail-closed 到 PUBLIC，不做数值巧合直查。
     */
    @Override
    public org.ruoyi.service.knowledge.RetrievalAccessProfile retrievalAccessProfile(Long userId) {
        return org.ruoyi.service.knowledge.RetrievalAccessProfile.FAIL_CLOSED_PUBLIC;
    }
}
