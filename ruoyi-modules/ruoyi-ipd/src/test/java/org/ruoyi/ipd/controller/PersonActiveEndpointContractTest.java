package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.service.PersonService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R118 契约单测（SSOT=scripts/check-e2e-fe-be.sh L169）：
 * {@code GET /api/v1/persons/active} → 200 + code=0 + data.persons[].id/name（真库 persons 非 MOCK）。
 *
 * <p>「在职」口径锁：employment_status='ACTIVE'（雇佣维度；account_status 登录维度不滤，
 * 与 PersonService 状态机 EM_ACTIVE 常量同源）+ @TableLogic 软删 + id 升序稳定排序。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class PersonActiveEndpointContractTest {

    @Mock
    private PersonService personService;
    @Mock
    private IpdPermission permission;

    @InjectMocks
    private PersonController controller;

    /** 真库 persons 在职行真实形状（在职 + 未删；含在职但账户 DISABLED 的合法组合）。 */
    private static Person active(long id, String name, String type, Long groupId, String account) {
        return Person.builder()
            .id(id).name(name).personType(type).groupId(groupId)
            .employmentStatus(PersonService.EM_ACTIVE).accountStatus(account)
            .delFlag("0").build();
    }

    private static List<Person> activePersons() {
        return List.of(
            active(9110002L, "杨波", "MARKET_PM", 900001L, "ACTIVE"),
            active(9110003L, "段进科", "RD_PM", 900001L, "ACTIVE"),
            active(9110001L, "傅志谦", "MARKET_PM", null, "DISABLED"));
    }

    @Test
    @DisplayName("[R118-1] URL 契约锁：GET /api/v1/persons/active；鉴权=方法内 requireInternal（不挂注解、不发明新码）")
    void urlBinding_locked() throws Exception {
        RequestMapping cls = PersonController.class.getAnnotation(RequestMapping.class);
        assertThat(cls.value()).contains("/api/v1/persons");
        Method m = PersonController.class.getDeclaredMethod("listActive");
        GetMapping get = m.getAnnotation(GetMapping.class);
        assertThat(get).isNotNull();
        assertThat(get.value()).containsExactly("/active");
        // 本域既有范式：resign/rehire/unbind 均无 @SaCheckPermission，读端点同款不新增权限码
        assertThat(m.getAnnotation(SaCheckPermission.class)).isNull();
    }

    @Test
    @DisplayName("[R118-2] 正常返回：code=0 + data.persons[].id/name（id 字符串化，服务返回序=SQL id 升序透传）")
    void active_returns_envelope_with_id_name() {
        when(permission.requireInternal())
            .thenReturn(new IpdActor(900101L, "ipd-admin", "SUPER_ADMIN", null));
        when(personService.listActive()).thenReturn(activePersons());

        ApiV1Response<PersonController.ActivePersonsView> resp = controller.listActive();

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData().persons()).hasSize(3);
        assertThat(resp.getData().persons()).extracting(PersonController.ActivePersonView::id)
            .containsExactly("9110002", "9110003", "9110001");
        assertThat(resp.getData().persons()).extracting(PersonController.ActivePersonView::name)
            .containsExactly("杨波", "段进科", "傅志谦");
        assertThat(resp.getData().persons().get(2).groupId()).isNull();
        assertThat(resp.getData().persons().get(0).groupId()).isEqualTo("900001");
    }

    @Test
    @DisplayName("[R118-3] 空态：无在职人员 → data.persons=[] 不抛（code=0）")
    void active_empty_source_returns_empty_list() {
        when(permission.requireInternal())
            .thenReturn(new IpdActor(900103L, "ipd-market", "MARKET_PM", 10L));
        when(personService.listActive()).thenReturn(List.of());

        ApiV1Response<PersonController.ActivePersonsView> resp = controller.listActive();

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData().persons()).isEmpty();
    }

    @Test
    @DisplayName("[R118-4] 未认证 fail-closed：requireInternal 抛 401 透传，service 零调用")
    void active_requires_internal_session() {
        when(permission.requireInternal())
            .thenThrow(new IpdPermissionException(401, ApiV1ErrorCode.UNAUTHORIZED));

        assertThatThrownBy(() -> controller.listActive())
            .isInstanceOf(IpdPermissionException.class);
        verify(personService, never()).listActive();
    }

    @Test
    @DisplayName("[R118-5] JSON 形状锁：data.persons[0].id 为字符串 + name 非空（契约 persons[].id/name）")
    void active_json_shape_string_ids() throws Exception {
        var view = new PersonController.ActivePersonsView(
            activePersons().stream()
                .map(p -> new PersonController.ActivePersonView(String.valueOf(p.getId()),
                    p.getName(), p.getPersonType(),
                    p.getGroupId() == null ? null : String.valueOf(p.getGroupId())))
                .toList());
        JsonNode root = new ObjectMapper().valueToTree(ApiV1Response.ok(view));
        assertThat(root.path("code").asInt()).isEqualTo(0);
        JsonNode first = root.path("data").path("persons").get(0);
        assertThat(first.path("id").isTextual()).isTrue();
        assertThat(first.path("id").asText()).isEqualTo("9110002");
        assertThat(first.path("name").asText()).isEqualTo("杨波");
    }

    // ------------------------------------------------------------------
    // 服务层查询链锁（在职口径唯一权威源：employment_status='ACTIVE' + id 升序）
    // ------------------------------------------------------------------

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
            new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Person.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("[R118-6] 查询链锁：listActive = employment_status='ACTIVE' + ORDER BY id（软删由 @TableLogic 注入）")
    void listActive_query_chain_locked() {
        PersonMapper personMapper = Mockito.mock(PersonMapper.class);
        PersonService svc = new PersonService(personMapper, null, null, null, null);
        when(personMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(activePersons());

        List<Person> rows = svc.listActive();

        assertThat(rows).hasSize(3);
        ArgumentCaptor<LambdaQueryWrapper<Person>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(personMapper).selectList(captor.capture());
        LambdaQueryWrapper<Person> w = captor.getValue();
        assertThat(w.getSqlSegment())
            .contains("employment_status =")
            .contains("ORDER BY id ASC");
        assertThat(w.getParamNameValuePairs().values()).contains("ACTIVE");
    }
}
