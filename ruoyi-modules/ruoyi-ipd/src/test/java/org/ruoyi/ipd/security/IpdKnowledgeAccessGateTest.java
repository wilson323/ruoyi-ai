package org.ruoyi.ipd.security;

import cn.dev33.satoken.exception.NotLoginException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.service.knowledge.RetrievalAccessProfile;
import org.ruoyi.service.knowledge.impl.UserIdShareKnowledgeAccessGate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2 检索接线（实施方案 §3.2 / 最佳实践 §8.2）：「角色→敏感级上限」权威表唯一落点
 * {@link IpdKnowledgeAccessGate} 的判据直测。
 * <p>
 * 矩阵纪律：三档 cap 全断言（SECRET/INTERNAL/PUBLIC），禁序比较——上轮 P0 教训
 * （字典序 INTERNAL&lt;PUBLIC&lt;SECRET 与敏感级升序相反）。fail-closed：无 ipd 会话 /
 * 会话侧异常 / sys_user（双参，无 persons 映射）一律 PUBLIC——拒绝高于 PUBLIC 的一切
 * 而非放开（P2-3：SECRET 级不因集合语义放开，非授权身份拿不到 SECRET cap）。
 * B0 判据整体委托 baseGate（组合而非继承）——零漂移断言见 delegation 用例。
 */
@Tag("dev")
class IpdKnowledgeAccessGateTest {

    private static Person person(long id, String personType) {
        Person person = new Person();
        person.setId(id);
        person.setPersonType(personType);
        return person;
    }

    private static IpdKnowledgeAccessGate gateOver(UserIdShareKnowledgeAccessGate baseGate,
                                                   IpdAuthSession authSession) {
        return new IpdKnowledgeAccessGate(baseGate, authSession);
    }

    @Test
    @DisplayName("权威表矩阵：SUPER_ADMIN/GROUP_LEADER→SECRET；双 PM→INTERNAL；其余→PUBLIC")
    void roleMatrixMapsPersonTypeToSensitivityCap() {
        assertThat(IpdKnowledgeAccessGate.maxSensitivityOf("SUPER_ADMIN")).isEqualTo("SECRET");
        assertThat(IpdKnowledgeAccessGate.maxSensitivityOf("GROUP_LEADER")).isEqualTo("SECRET");
        assertThat(IpdKnowledgeAccessGate.maxSensitivityOf("MARKET_PM")).isEqualTo("INTERNAL");
        assertThat(IpdKnowledgeAccessGate.maxSensitivityOf("RD_PM")).isEqualTo("INTERNAL");
        // fail-closed 半边：未知/低权/null 拿不到高于 PUBLIC 的 cap（P2-3 断言核心）
        assertThat(IpdKnowledgeAccessGate.maxSensitivityOf("GUEST")).isEqualTo("PUBLIC");
        assertThat(IpdKnowledgeAccessGate.maxSensitivityOf("RESEARCHER_UNKNOWN")).isEqualTo("PUBLIC");
        assertThat(IpdKnowledgeAccessGate.maxSensitivityOf(null)).isEqualTo("PUBLIC");
        assertThat(IpdKnowledgeAccessGate.maxSensitivityOf("")).isEqualTo("PUBLIC");
    }

    @Test
    @DisplayName("ipd 会话在座：profile 携带对应档 cap；归属键恒 null（P1-1 值域防护）")
    void profileFromIpdSessionCarriesCapWithoutPersonId() {
        IpdAuthSession authSession = mock(IpdAuthSession.class);
        IpdKnowledgeAccessGate gate = gateOver(mock(UserIdShareKnowledgeAccessGate.class), authSession);

        when(authSession.currentPerson()).thenReturn(person(900101L, "GROUP_LEADER"));
        RetrievalAccessProfile leader = gate.retrievalAccessProfile();
        assertThat(leader.maxSensitivity()).isEqualTo("SECRET");
        assertThat(leader.personId())
            .as("persons 900xxx 与向量 payload owner_person_id（sys_user 小整数）值域不相交，装上即全量误杀")
            .isNull();

        when(authSession.currentPerson()).thenReturn(person(900103L, "MARKET_PM"));
        RetrievalAccessProfile pm = gate.retrievalAccessProfile();
        assertThat(pm.maxSensitivity()).isEqualTo("INTERNAL");
        assertThat(pm.personId()).isNull();
    }

    @Test
    @DisplayName("无 ipd 会话（sys_user/匿名）：fail-closed PUBLIC，绝不放 INTERNAL/SECRET")
    void noIpdSessionDegradesToPublicFailClosed() {
        IpdAuthSession authSession = mock(IpdAuthSession.class);
        IpdKnowledgeAccessGate gate = gateOver(mock(UserIdShareKnowledgeAccessGate.class), authSession);

        when(authSession.currentPerson()).thenThrow(NotLoginException.newInstance(
            IpdAuthSession.LOGIN_TYPE, NotLoginException.INVALID_TOKEN, "凭证已更新，请重新登录", "tk"));
        RetrievalAccessProfile profile = gate.retrievalAccessProfile();
        assertThat(profile.maxSensitivity()).isEqualTo("PUBLIC");
        assertThat(profile.personId()).isNull();
    }

    @Test
    @DisplayName("会话侧意外异常同样降档 PUBLIC：装配值供应不得因异常放开")
    void sessionSideUnexpectedExceptionAlsoDegradesPublic() {
        IpdAuthSession authSession = mock(IpdAuthSession.class);
        IpdKnowledgeAccessGate gate = gateOver(mock(UserIdShareKnowledgeAccessGate.class), authSession);

        when(authSession.currentPerson()).thenThrow(new RuntimeException("db down"));
        assertThat(gate.retrievalAccessProfile().maxSensitivity()).isEqualTo("PUBLIC");
    }

    @Test
    @DisplayName("双参变体（sys_user 显式身份）：无 persons 映射前恒 PUBLIC（现态登记）")
    void twoArgVariantFailsClosedUntilPersonMappingExists() {
        IpdKnowledgeAccessGate gate = gateOver(
            mock(UserIdShareKnowledgeAccessGate.class), mock(IpdAuthSession.class));

        RetrievalAccessProfile profile = gate.retrievalAccessProfile(1L);
        assertThat(profile.maxSensitivity())
            .as("sys_user id 与 persons id 空间不相交，不做数值巧合直查（提权面）")
            .isEqualTo("PUBLIC");
        assertThat(profile.personId()).isNull();
    }

    @Test
    @DisplayName("B0 判据零漂移：四方法整体委托 baseGate（组合而非继承）")
    void b0SemanticsFullyDelegatedToBaseGate() {
        UserIdShareKnowledgeAccessGate baseGate = mock(UserIdShareKnowledgeAccessGate.class);
        IpdKnowledgeAccessGate gate = gateOver(baseGate, mock(IpdAuthSession.class));

        gate.checkRetrievalAccess(9L);
        gate.checkRetrievalAccess(9L, 100L);
        gate.assertManageable(9L);
        gate.assertManageable(9L, 100L);

        verify(baseGate).checkRetrievalAccess(9L);
        verify(baseGate).checkRetrievalAccess(9L, 100L);
        verify(baseGate).assertManageable(9L);
        verify(baseGate).assertManageable(9L, 100L);
    }
}
