package org.ruoyi.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.ruoyi.common.social.config.properties.SocialProperties;
import org.ruoyi.ipd.controller.IpdPlatformAuthController;
import org.ruoyi.system.service.ISysClientService;
import org.ruoyi.system.service.ISysConfigService;
import org.ruoyi.system.service.SysRegisterService;
import org.ruoyi.system.service.ISysSocialService;
import org.ruoyi.system.service.ISysTenantService;
import org.ruoyi.system.service.SysLoginService;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录契约唯一化哨兵（双轨裁决 2026-09-28 login-single-track）。
 *
 * 背景：本仓曾并存两套密码登录契约——平台 {@code POST /auth/login}（sys_user 域，
 * LoginBody 要求 clientId+grantType）与 IPD {@code POST /api/v1/auth/login}（Person 域）。
 * 契约错位的实测症状是 code 500「请求参数校验失败」，且前端 2026-09-11 已踩过一次
 * （见 ruoyi-ipd-web apps/web-antd/src/api/core/auth.ts 登录注释）。owner 2026-09-28 拍板：
 * 禁止双轨、统一到占比多数的 IPD 契约（前端 67 处调用点全走 requestIpd，仓库内平台
 * /auth/login HTTP 调用方为 0）。
 *
 * 本哨兵把裁决机制化：
 * 1. 平台 /auth/login 恒 410 Gone 且返回统一契约指引（不许静默恢复密码直登）；
 * 2. 所有签发登录态的 POST 端点必须位于 /api/v1/auth 契约族（410 的 /auth/login 除外），
 *    新增任何域外登录端点本测试即红。
 */
@Tag("dev")
class LoginContractSingleTrackTest {

    /** 410 专属保留映射（存量误用方指引位），不属于可登录契约。 */
    private static final String RETIRED_PLATFORM_LOGIN = "org.ruoyi.controller.AuthController#/auth/login";

    private static AuthController newController() {
        SocialProperties socialProperties = new SocialProperties();
        socialProperties.setType(new HashMap<>());
        return new AuthController(
            socialProperties,
            Mockito.mock(SysLoginService.class),
            Mockito.mock(SysRegisterService.class),
            Mockito.mock(ISysConfigService.class),
            Mockito.mock(ISysTenantService.class),
            Mockito.mock(ISysSocialService.class),
            Mockito.mock(ISysClientService.class));
    }

    @Test
    void platformPasswordLoginIsGoneWithUnifiedContractHint() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(newController()).build();
        String body = mvc.perform(post("/auth/login").contentType("application/json").content("{}"))
            .andExpect(status().isGone())
            .andReturn().getResponse().getContentAsString();
        assertThat(body)
            .as("410 响体必须指明唯一登录契约，防止误用方继续猜契约")
            .contains("/api/v1/auth/login")
            .contains("login-single-track");
    }

    @Test
    void loginEndpointMappingsAreUnique() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> loginPostEndpoints = new LinkedHashSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("org.ruoyi")) {
            Class<?> cls = Class.forName(bd.getBeanClassName());
            String base = Optional.ofNullable(cls.getAnnotation(RequestMapping.class))
                .map(rm -> String.join("", rm.value())).orElse("");
            for (Method m : cls.getDeclaredMethods()) {
                PostMapping pm = m.getAnnotation(PostMapping.class);
                if (pm == null) {
                    continue;
                }
                String path = base + String.join("", pm.value());
                if (isLoginIssuingPath(path)) {
                    loginPostEndpoints.add(cls.getName() + "#" + path);
                }
            }
        }

        assertThat(loginPostEndpoints)
            .as("签发登录态的 POST 端点只许两类：/api/v1/auth 契约族 + 410 专属 /auth/login。"
                + "出现域外登录端点 = 双轨再生，禁止合并前必须走裁决")
            .allSatisfy(ep -> {
                boolean inIpdContract = ep.contains("#/api/v1/auth/");
                boolean isRetired410 = RETIRED_PLATFORM_LOGIN.equals(ep);
                assertThat(inIpdContract || isRetired410)
                    .as("域外登录端点（双轨）：" + ep)
                    .isTrue();
            })
            .contains(RETIRED_PLATFORM_LOGIN);
    }

    @Test
    void ipdLoginAndPlatformTicketEndpointsRemainWired() {
        // 唯一密码登录契约（Person 凭据，code0 包络）
        Set<String> ipdLogin = findPostMappings("org.ruoyi.ipd.controller.IpdAuthController");
        assertThat(ipdLogin).as("IPD 登录契约映射不得被拆").anyMatch(p -> p.endsWith("/api/v1/auth/login"));
        // 唯一平台票签发口（IPD 会话 → Sa-Token 换票；不走密码直登）
        Set<String> ticket = findPostMappings("org.ruoyi.ipd.controller.IpdPlatformAuthController");
        assertThat(ticket).as("平台换票端点不得被拆").anyMatch(p -> p.endsWith("/api/v1/auth/platform-token"));
        // 类注解自证（防有人改类级 @RequestMapping 绕开方法级断言）
        assertThat(Optional.ofNullable(load("org.ruoyi.ipd.controller.IpdAuthController")
            .getAnnotation(RequestMapping.class)).map(rm -> String.join("", rm.value())).orElse(""))
            .isEqualTo("/api/v1/auth");
    }

    @Test
    void platformTicketResponseDeliversClientId() {
        // clientid-contract 2026-09-28：基线 /system/** 校验「请求头 clientid == token extra clientid」
        // （SecurityConfig.check），换票响应必须交付该值（UUID）；"pc" 仅为 client_key（登录查询键），
        // 调用方不得再靠解 JWT 猜、更不得把 client_key 当 clientid 带（实测 401 伪装成未登录）。
        assertThat(Arrays.stream(IpdPlatformAuthController.PlatformTokenView.class.getRecordComponents())
                .map(RecordComponent::getName))
            .as("PlatformTokenView 必须交付 clientId（基线鉴权头契约的唯一权威值）")
            .contains("clientId");
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("契约类缺失（双轨裁决相关类被移除？）：" + name, e);
        }
    }

    /**
     * 判定"签发登录态"的路径：以路径段精确匹配 login 或 *-login（如 wecom/qr-login），
     * 而非子串 contains("login")——避免误伤 logininfor（登录日志）等非登录端点。
     */
    private static boolean isLoginIssuingPath(String path) {
        for (String seg : path.split("/")) {
            String s = seg.toLowerCase();
            if (s.equals("login") || s.endsWith("-login")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> findPostMappings(String className) {
        Class<?> cls = load(className);
        String base = Optional.ofNullable(cls.getAnnotation(RequestMapping.class))
            .map(rm -> String.join("", rm.value())).orElse("");
        Set<String> out = new LinkedHashSet<>();
        for (Method m : cls.getDeclaredMethods()) {
            PostMapping pm = m.getAnnotation(PostMapping.class);
            if (pm != null) {
                out.add(base + String.join("", pm.value()));
            }
        }
        return out;
    }
}
