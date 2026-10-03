package org.ruoyi.ipd.security;

import cn.dev33.satoken.exception.SaTokenException;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.knowledge.RetrievalAccessProfile;
import org.ruoyi.service.knowledge.impl.UserIdShareKnowledgeAccessGate;

/**
 * {@link KnowledgeAccessGate} 的 IPD 权威实现（B2 检索接线，实施方案 §3.2）。
 * <p>
 * 「角色→敏感级上限」映射表<b>全仓唯一落点在本类</b>（最佳实践 §8.2：上层权威源
 * 在 IPD 侧，ruoyi-chat 不内嵌该表）：
 * <ul>
 *   <li>SUPER_ADMIN / GROUP_LEADER → SECRET；</li>
 *   <li>MARKET_PM / RD_PM → INTERNAL；</li>
 *   <li>其余（未知 personType / 无 ipd 会话 / sys_user / 匿名）→ PUBLIC fail-closed
 *       ——与 {@link IpdRolePermissionCatalog} 未知类型返空=拒绝一切同构：
 *       拒绝高于 PUBLIC 的一切，而非放开。</li>
 * </ul>
 * B0 判据（kid 归属 owned/share、管理面 owned）整体委托
 * {@link UserIdShareKnowledgeAccessGate}（组合而非继承——B0 语义零漂移，
 * 其双参变体/豁免逻辑/错误文案全部原样复用），本类只叠加 profile 判定。
 * <p>
 * 部署形态：经 {@code IpdKnowledgeAccessConfig} 以 @Primary 注册，覆盖 chat 侧
 * 默认 Bean（依赖方向 ruoyi-ipd → ruoyi-chat 单向，B0 审计 Q2 登记的替换路径）。
 * <p>
 * 现态登记（过渡）：知识库管理/检索链当前身份锚是 sys_user（基线 loginType），
 * ipd 会话（loginType=ipd）尚未接入检索调用方——本桥 profile 的 ipd 分支在
 * 运行态暂不会被命中，sys_user/匿名一律 PUBLIC；身份体系切换（IPD 前端改走
 * Person 会话或建立 sys_user→person 映射）后自然生效，无需改本表。
 * 归属键暂缓装配（Validator P1-1）：向量 payload 写入侧 owner_person_id 取的是
 * sys_user 小整数，与本表 persons 900xxx 空间不相交——映射建立前装 personId
 * 会让归属谓词对全部存量对象误杀（检索恒空），故 ipd 分支只装 maxSensitivity，
 * personId 待写入侧同步改用 person id 后再补。
 * 检索装配开关（knowledge.retrieval.access-filter.enabled）默认关闭，
 * PUBLIC 收紧面在开启前零运行态影响。
 * <p>
 * <b>开启该开关的前置条件（2026-10-03 显式登记，未满足前不要打开）</b>：
 * 须先让上面的 ipd 分支在运行态真正可命中——即 IPD 用户的检索调用方能解析出
 * {@code currentPerson()}（做法：IPD 前端改走 Person 会话，或建立 sys_user→person 映射）。
 * 原因：本类 {@code retrievalAccessProfile()} 在 ipd 分支不可命中时只能返回
 * {@link RetrievalAccessProfile#FAIL_CLOSED_PUBLIC}。此时若打开开关，maxSensitivity 会对
 * <b>所有</b>身份（含 SUPER_ADMIN / GROUP_LEADER）一律收成 PUBLIC —— 非 PUBLIC 知识库
 * （含各 PM 自建 INTERNAL 库）召回被整体收窄，这是**功能回退且不换回任何安全收益**
 * （角色本就无法被区分，收紧只是把所有人一起关小）。故正确顺序是：先通身份，再开开关；
 * 两者都就绪后再补归属键（见上 P1-1）。开关自身的开/关行为差异由 chat 侧
 * KnowledgeRetrievalBridgeAssemblyTest 覆盖（disabledSwitch… / enabledSwitch… 两用例）。
 *
 * @author ruoyi
 * @date 2026-09-28
 */
@Slf4j
public class IpdKnowledgeAccessGate implements KnowledgeAccessGate {

    /** 角色→敏感级上限权威表（§8.2 映射；switch 显式分档，不依赖字典序/声明序）。 */
    static String maxSensitivityOf(String personType) {
        return switch (personType == null ? "" : personType) {
            case "SUPER_ADMIN", "GROUP_LEADER" -> "SECRET";
            case "MARKET_PM", "RD_PM" -> "INTERNAL";
            default -> "PUBLIC";
        };
    }

    private final UserIdShareKnowledgeAccessGate baseGate;
    private final IpdAuthSession authSession;

    public IpdKnowledgeAccessGate(UserIdShareKnowledgeAccessGate baseGate, IpdAuthSession authSession) {
        this.baseGate = baseGate;
        this.authSession = authSession;
    }

    // ---------- B0 判据：整体委托（零漂移） ----------

    @Override
    public void checkRetrievalAccess(Long kid) {
        baseGate.checkRetrievalAccess(kid);
    }

    @Override
    public void checkRetrievalAccess(Long kid, Long userId) {
        baseGate.checkRetrievalAccess(kid, userId);
    }

    @Override
    public void assertManageable(Long kid) {
        baseGate.assertManageable(kid);
    }

    @Override
    public void assertManageable(Long kid, Long userId) {
        baseGate.assertManageable(kid, userId);
    }

    // ---------- B2 profile 判定（角色→上限权威表唯一落点） ----------

    @Override
    public RetrievalAccessProfile retrievalAccessProfile() {
        Person person = currentIpdPersonOrNull();
        if (person != null) {
            // 归属键恒 null（Validator P1-1）：owner_person_id 写入侧是 sys_user 小整数，
            // 与 persons 900xxx 不相交——装本空间 id 即全量误杀；映射建立前只装敏感级上限。
            return new RetrievalAccessProfile(maxSensitivityOf(person.getPersonType()), null,
                null, null, null, null);
        }
        // sys_user / 匿名 fallback（§3.2）：persons 无 sys_user 映射列且 id 空间不相交
        //（sys_user 小整数 vs persons 900xxx），不做数值巧合直查（提权面），fail-closed PUBLIC。
        return RetrievalAccessProfile.FAIL_CLOSED_PUBLIC;
    }

    @Override
    public RetrievalAccessProfile retrievalAccessProfile(Long userId) {
        // 非 HTTP 线程（@Async 等）：无 Sa-Token 上下文可解析 ipd 会话；
        // 入参 userId 为 sys_user id，同上无映射可查，fail-closed PUBLIC
        //（身份体系建立映射后在此扩展，见类注释现态登记）。
        return RetrievalAccessProfile.FAIL_CLOSED_PUBLIC;
    }

    /**
     * 当前线程的 ipd 会话 Person；无 ipd 会话/凭证失效/上下文不可用返回 null
     * （不抛——profile 是装配值供应，取不到身份即降档，见端口 javadoc）。
     */
    private Person currentIpdPersonOrNull() {
        try {
            return authSession.currentPerson();
        } catch (SaTokenException e) {
            // NotLoginException 是 SaTokenException 子类（multi-catch 并列属冗余），
            // 单捕父类即涵盖未登录/凭证失效；取不到身份即降档，见端口 javadoc。
            return null;
        } catch (Exception e) {
            // 防御：会话侧任何意外（如数据访问异常）都不得把 profile 装配变成放开
            log.warn("ipd 会话解析异常，检索 profile 按 fail-closed 处理: {}", e.getMessage());
            return null;
        }
    }
}
