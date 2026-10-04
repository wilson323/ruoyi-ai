package org.ruoyi.ipd.config;

import cn.hutool.crypto.digest.BCrypt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生产「第一个人」引导的四态验证（2026-10-03）。
 *
 * <p>这个类的存在意义是「空库时无人可登录」——所以每条测试都在钉一个具体后果：
 * <ol>
 *   <li><b>开关关闭</b>：默认行为必须是什么都不做。若这条红了，等于代码会替部署者
 *       擅自往生产库写超管；</li>
 *   <li><b>库非空</b>：即使开关打开也必须整体跳过。若这条红了，等于重复启动会二次插入，
 *       或可能改写既有人员数据；</li>
 *   <li><b>口令为空</b>：必须抛异常拒绝。若这条红了，等于可能落一个默认口令账号进生产库
 *       —— 静默留下可猜口令的超管，比启动失败危险得多；</li>
 *   <li><b>正常引导</b>：写入的行必须是超管、必须强制首次改密、口令哈希必须能被环境变量
 *       里的原口令验开（反证：不是硬编码口令、也没写成明文）。</li>
 * </ol>
 *
 * <p>@Tag("dev") 必须：Surefire 以 groups=${profiles.active} 过滤，缺 tag 会被静默跳过（假绿陷阱）。
 */
@Tag("dev")
@DisplayName("生产首管理员引导：四态")
@ExtendWith(MockitoExtension.class)
class IpdProdAdminBootstrapTest {

    @Mock
    private PersonMapper personMapper;

    private IpdProdAdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new IpdProdAdminBootstrap(personMapper);
        ReflectionTestUtils.setField(bootstrap, "enabled", false);
        ReflectionTestUtils.setField(bootstrap, "adminUsername", "admin");
        ReflectionTestUtils.setField(bootstrap, "adminName", "系统管理员");
        ReflectionTestUtils.setField(bootstrap, "initialPassword", "");
    }

    @Test
    @DisplayName("开关关闭时什么都不做——不查库、不写入")
    void disabledDoesNothing() {
        bootstrap.run(null);

        verify(personMapper, never()).insert(any(Person.class));
        // 连计数都不该发：关闭态不产生任何数据库往返
        verify(personMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("库非空时整体跳过——绝不改写既有人员数据")
    void skipsWhenPersonsNotEmpty() {
        ReflectionTestUtils.setField(bootstrap, "enabled", true);
        when(personMapper.selectCount(any())).thenReturn(116L);

        bootstrap.run(null);

        verify(personMapper, never()).insert(any(Person.class));
    }

    @Test
    @DisplayName("口令为空时拒绝创建——不落任何默认口令账号")
    void refusesWhenPasswordBlank() {
        ReflectionTestUtils.setField(bootstrap, "enabled", true);
        when(personMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> bootstrap.run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("IPD_INITIAL_PWD");

        verify(personMapper, never()).insert(any(Person.class));
    }

    @Test
    @DisplayName("空库 + 有口令：写入超管，强制改密，哈希可被原口令验开")
    void createsSuperAdminOnEmptyDatabase() {
        String rawPassword = "UnitTest-Pwd-Only";
        ReflectionTestUtils.setField(bootstrap, "enabled", true);
        ReflectionTestUtils.setField(bootstrap, "initialPassword", rawPassword);
        when(personMapper.selectCount(any())).thenReturn(0L);

        bootstrap.run(null);

        ArgumentCaptor<Person> captor = ArgumentCaptor.forClass(Person.class);
        verify(personMapper).insert(captor.capture());
        Person created = captor.getValue();

        assertThat(created.getPersonType()).isEqualTo("SUPER_ADMIN");
        assertThat(created.getUsername()).isEqualTo("admin");
        assertThat(created.getAccountStatus()).isEqualTo("ACTIVE");
        assertThat(created.getEmploymentStatus()).isEqualTo("ACTIVE");
        // 首次登录必须被要求改密，否则引导出来的账号会长期用部署时的口令
        assertThat(created.getMustChangePwd()).isEqualTo("1");
        // 反证口令不是硬编码、也没写成明文：哈希必须能被传进来的原口令验开，且不等于明文
        assertThat(created.getPasswordHash()).isNotEqualTo(rawPassword);
        assertThat(BCrypt.checkpw(rawPassword, created.getPasswordHash())).isTrue();
        assertThat(BCrypt.checkpw("wrong-password", created.getPasswordHash())).isFalse();
    }
}
