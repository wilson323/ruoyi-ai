package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.dto.AiModelSaveReq;
import org.ruoyi.ipd.mapper.AiModelConfigMapper;
import org.ruoyi.ipd.service.ai.EndpointUrlValidator;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-3（D 轮 D2 SSRF-01 / D4 N-3，2026-09-29）：模型端点 SSRF 黑名单回归（D2 P2 判据命名）。
 * <ul>
 *   <li>B 路（保存入口）：create/update 拒绝 RFC1918 / 链路本地 / 回环 / 云元数据地址段——
 *       D2 SSRF3 判据（169.254.169.254 + 有效 key 应 400 而非 200）；</li>
 *   <li>D2 SSRF2 判据顺序钉扎：短 key 场景仍先撞「API 密钥」文案（SSRF 闸置于全部字段校验之后）；</li>
 *   <li>解析器一致性：十进制（2130706433）/十六进制（0x7f000001）IP 字面量同被拦截
 *       （2026-09-29 jshell 实证两者均解析到 127.0.0.1，校验器与出站连接同一 InetAddress 解析器）；</li>
 *   <li>ai.allowed-hosts 豁免双向（R184-A/R212 语义）：命中豁免 loopback，不豁免云元数据；</li>
 *   <li>保存侧解析失败 fail-open（unresolvable host 可保存，出站 C 路 fail-closed 兜底）。</li>
 * </ul>
 */
@Tag("dev")
@DisplayName("P0-3 模型端点 SSRF 黑名单：EndpointUrlValidator + 保存入口负控")
class AiModelEndpointValidatorTest {

    private static final String ENV_KEY = "unit-test-master-key-32bytes!!!!";

    private AiModelConfigMapper mapper;
    private IAuditLogService audit;
    private AiModelConfigService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiModelConfig.class);
        mapper = mock(AiModelConfigMapper.class);
        audit = mock(IAuditLogService.class);
        when(audit.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AiModelConfigService(mapper, audit, ENV_KEY);
    }

    private static AiModelSaveReq req(String endpoint, String apiKey) {
        return new AiModelSaveReq("openai", endpoint, apiKey, "m", BigDecimal.ZERO, 100, null, null, null);
    }

    private static AiModelSaveReq reqWithEmbed(String endpoint, String embedEndpoint) {
        return new AiModelSaveReq("openai", endpoint, "sk-1234567890", "m", null, null, embedEndpoint, null, null);
    }

    private void setAllowedHosts(String value) throws Exception {
        Field f = AiModelConfigService.class.getDeclaredField("allowedHosts");
        f.setAccessible(true);
        f.set(service, value);
    }

    // ==================== 黑名单：IPv4 内网段 ====================

    @Test
    @DisplayName("云元数据 169.254.169.254 拦截（D2 SSRF3 核心判据）")
    void metadataIpBlocked() {
        assertNotNull(EndpointUrlValidator.blockReason("http://169.254.169.254/latest/meta-data/", ""));
        assertNotNull(EndpointUrlValidator.blockReason("http://169.254.169.254/latest/meta-data/", null));
    }

    @Test
    @DisplayName("loopback 127.0.0.0/8 拦截")
    void loopbackBlocked() {
        assertNotNull(EndpointUrlValidator.blockReason("http://127.0.0.1:11434/v1", ""));
        assertNotNull(EndpointUrlValidator.blockReason("http://127.0.0.2/", ""));
    }

    @Test
    @DisplayName("RFC1918 私网 10/8、172.16/12、192.168/16 拦截")
    void rfc1918Blocked() {
        assertNotNull(EndpointUrlValidator.blockReason("http://10.0.0.1/v1", ""));
        assertNotNull(EndpointUrlValidator.blockReason("http://172.16.0.1/v1", ""));
        assertNotNull(EndpointUrlValidator.blockReason("http://192.168.1.1/v1", ""));
    }

    @Test
    @DisplayName("CGN 100.64.0.0/10 与 0.0.0.0/8 拦截")
    void cgnAndAnyLocalBlocked() {
        assertNotNull(EndpointUrlValidator.blockReason("http://100.64.0.1/", ""));
        assertNotNull(EndpointUrlValidator.blockReason("http://0.0.0.0/", ""));
        assertNotNull(EndpointUrlValidator.blockReason("http://0.0.0.1/", ""));
    }

    // ==================== 黑名单：IPv6 与字面量变体 ====================

    @Test
    @DisplayName("IPv6 ::1 / fe80:: / fd00::（ULA）拦截")
    void ipv6Blocked() {
        assertNotNull(EndpointUrlValidator.blockReason("http://[::1]:8080/v1", ""));
        assertNotNull(EndpointUrlValidator.blockReason("http://[fe80::1]/", ""));
        assertNotNull(EndpointUrlValidator.blockReason("http://[fd00::1]/", ""));
    }

    @Test
    @DisplayName("数字字面量 IP 变体拦截：十进制 2130706433、十六进制 0x7f000001（同解析器实证）")
    void numericLiteralIpBlocked() {
        assertNotNull(EndpointUrlValidator.blockReason("http://2130706433/", ""),
            "十进制单段字面量 = 127.0.0.1，必须拦截");
        assertNotNull(EndpointUrlValidator.blockReason("http://0x7f000001/", ""),
            "十六进制字面量 = 127.0.0.1，必须拦截");
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 ::ffff:127.0.0.1 解包后拦截（防 mapped 形态绕过）")
    void ipv4MappedBlocked() {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = 127;
        mapped[15] = 1;
        assertTrue(EndpointUrlValidator.isBlockedIp(mapped), "::ffff:127.0.0.1 必须拦截");
    }

    // ==================== 放行面 ====================

    @Test
    @DisplayName("公网目标放行：8.8.8.8 / api.openai.com")
    void publicTargetPasses() {
        assertNull(EndpointUrlValidator.blockReason("http://8.8.8.8/", ""));
        assertNull(EndpointUrlValidator.blockReason("https://api.openai.com/v1", ""));
    }

    @Test
    @DisplayName("保存侧解析失败 fail-open：unresolvable host 可保存（出站 C 路 fail-closed 兜底）")
    void unresolvableHostFailsOpenAtSave() {
        assertNull(EndpointUrlValidator.blockReason("https://unresolvable-host.invalid/v1", ""),
            "保存不依赖 DNS 可用性；出站前 AiChatClient 抛 unresolvable 才是关口");
    }

    @Test
    @DisplayName("URL 形态拒绝：userinfo 内嵌 / 协议非 http(s) / 缺 host")
    void malformedUrlRejected() {
        assertNotNull(EndpointUrlValidator.blockReason("http://user@127.0.0.1/", ""), "userinfo 必拒");
        assertNotNull(EndpointUrlValidator.blockReason("file:///etc/passwd", ""), "非 http(s) 必拒");
        assertNotNull(EndpointUrlValidator.blockReason("", ""), "空串必拒");
    }

    // ==================== ai.allowed-hosts 豁免（R184-A/R212 双向） ====================

    @Test
    @DisplayName("豁免正例：allowlist 命中 127.0.0.1 → loopback 放行（dev/mock 语义）")
    void allowlistExemptsLoopback() {
        assertNull(EndpointUrlValidator.blockReason("http://127.0.0.1:11434/v1", "127.0.0.1"));
    }

    @Test
    @DisplayName("豁免反例：allowlist=127.0.0.1 不豁免云元数据（防豁免口变 SSRF 开口）")
    void allowlistDoesNotExemptMetadata() {
        assertNotNull(EndpointUrlValidator.blockReason("http://169.254.169.254/", "127.0.0.1"));
    }

    // ==================== 保存入口（B 路）：create/update 负控 ====================

    @Test
    @DisplayName("D2 SSRF3 判据：create 169.254.169.254 + 有效 key → PARAM_INVALID 且不入库")
    void createRejectsMetadataEndpoint() {
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.create(req("http://169.254.169.254/latest/meta-data/", "sk-1234567890"), "9"));
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("接口地址"), "定向文案必须含「接口地址」：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("SSRF"), "定向文案必须含 SSRF 语义：" + ex.getMessage());
        verify(mapper, never()).insert(any(AiModelConfig.class));
    }

    @Test
    @DisplayName("D2 SSRF2 判据顺序钉扎：短 key 先撞「API 密钥」文案（SSRF 闸在字段校验之后）")
    void shortKeyMessageWinsOverSsrfMessage() {
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.create(req("http://169.254.169.254/", "test"), "9"));
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("API 密钥"), "短 key 场景文案保持「API 密钥」：" + ex.getMessage());
    }

    @Test
    @DisplayName("update 同样拦截：改 endpoint 到 10.0.0.1 → PARAM_INVALID")
    void updateRejectsInternalEndpoint() {
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.update(1L, req("http://10.0.0.1/v1", null), "9"));
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("接口地址"));
        verify(mapper, never()).updateById(any(AiModelConfig.class));
    }

    @Test
    @DisplayName("embedEndpoint 同口径拦截：RAG 向量化端点 10.0.0.1 → PARAM_INVALID 且含「RAG」")
    void embedEndpointRejectsInternalTarget() {
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.create(reqWithEmbed("https://api.openai.com/v1", "http://10.0.0.1/"), "9"));
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("RAG"), "定向文案必须含「RAG」：" + ex.getMessage());
        verify(mapper, never()).insert(any(AiModelConfig.class));
    }

    @Test
    @DisplayName("allowlist 豁免生效：配置 127.0.0.1 后 mock 端点可保存（dev/mock 运营途径不断）")
    void allowlistAllowsMockEndpointSave() throws Exception {
        setAllowedHosts("127.0.0.1");
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(AiModelConfig.class))).thenAnswer(inv -> {
            inv.getArgument(0, AiModelConfig.class).setId(1L);
            return 1;
        });
        assertDoesNotThrow(() -> service.create(req("http://127.0.0.1:11434/v1", "sk-1234567890"), "9"));
    }

    @Test
    @DisplayName("公网端点正常保存（正例回归：收紧不得误伤合法目标）")
    void createAcceptsPublicEndpoint() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(AiModelConfig.class))).thenAnswer(inv -> {
            inv.getArgument(0, AiModelConfig.class).setId(1L);
            return 1;
        });
        assertDoesNotThrow(() -> service.create(req("https://api.openai.com/v1", "sk-1234567890"), "9"));
    }
}
