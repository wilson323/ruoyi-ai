/**
 * P1-4.2（卡 fde68b8c）真实附件上传下载和动作归属鉴权——卡面 5 项 AC 逐条钉死验收。
 *
 * 与相邻契约的分工：既有 {@link P142AcceptanceTest} 测的是 CONSISTENCY-13 客户端 ossId 回填契约，
 * 本类钉的是卡面验收要点原文五条（正反例齐备）：
 *   AC1 对象存在才登记        —— upload 后服务端 getById 回读；回读空 ⇒ 拒绝 insert + 补偿删除
 *   AC2 100MB 和格式策略      —— 超限/白名单外 ⇒ 不触达对象存储即拒绝（TS14:1150 口径）
 *   AC3 上传者/大小/hash 入库 —— uploadedBy=会话 actor、fileSize=实际字节、contentHash=SHA-256 hex；
 *                               并以真实 ipd_dev.deliverables 回环（随机 ID + ROLLBACK + 独立连接零残留核对），
 *                               避免「仅 Mock 不证明真实业务验收」（卡面验证要求原文）
 *   AC4 下载校验项目归属      —— 非在职成员 FORBIDDEN(30001) 且不触达 OSS；成员/SUPER_ADMIN 放行
 *   AC5 失败孤儿补偿          —— insert 失败 ⇒ deleteWithValidByIds 补偿删除已上传对象，原始异常透传
 *
 * 真库用例门控：默认读取本机 .codex/ipd-dev/config/mysql-app.cnf 与已安装 mysql-connector-j；
 * 环境缺失（文件不在/连不上）走 Assumptions.abort 显式跳过（不假绿）。
 * 可用 -Dipd.p142.clientConfig / -Dipd.p142.driverJar 覆盖路径。
 */
package org.ruoyi.ipd.service;

import cn.hutool.crypto.digest.DigestUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.domain.Deliverable;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class P142AttachmentAcceptanceTest {

    private static final long OSS_ID = 9001L;
    private static final byte[] CONTENT = "P142 评审纪要内容".getBytes();
    private static final IpdActor PM = new IpdActor(7L, "tester", "MARKET_PM", 900001L);
    private static final IpdActor ADMIN = new IpdActor(1L, "admin", "SUPER_ADMIN", null);

    @Mock private DeliverableMapper deliverableMapper;
    @Mock private StageActionMapper stageActionMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private ISysOssService ossService;
    @Mock private IAuditLogService auditLogService;

    @InjectMocks
    private DeliverableService service;

    @BeforeEach
    void fixture() {
        StageAction action = new StageAction();
        action.setId(100L);
        action.setProjectId(10L);
        Project project = new Project();
        project.setId(10L);
        project.setStatus("ACTIVE");
        project.setDelFlag("0");
        when(stageActionMapper.selectById(100L)).thenReturn(action);
        when(projectMapper.selectById(10L)).thenReturn(project);
    }

    /** 受控 multipart：大小/字节/文件名与真实上传体解耦（AC2 边界值构造需要）。 */
    private static MultipartFile multipart(String name, long size, byte[] bytes) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(bytes == null || bytes.length == 0);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getSize()).thenReturn(size);
        try {
            when(file.getBytes()).thenReturn(bytes == null ? new byte[0] : bytes);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return file;
    }

    private static SysOssVo ossVo(Long ossId, String url) {
        SysOssVo vo = new SysOssVo();
        vo.setOssId(ossId);
        vo.setUrl(url);
        return vo;
    }

    // ==================== AC1 对象存在才登记 ====================

    @Test
    @DisplayName("AC1 反例：OSS 对象回读不存在 ⇒ 拒绝登记（insert 零调用）且补偿删除孤儿对象")
    void ac1AbsentObjectRejectsRegistration() {
        when(ossService.upload(any(MultipartFile.class))).thenReturn(ossVo(OSS_ID, null));
        when(ossService.getById(OSS_ID)).thenReturn(null); // 对象登记不存在

        assertThatThrownBy(() -> service.upload(100L, multipart("纪要.pdf", CONTENT.length, CONTENT), PM))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("对象存在才登记");

        verify(deliverableMapper, never()).insert(any(Deliverable.class));
        verify(ossService).deleteWithValidByIds(eq(List.of(OSS_ID)), eq(false));
    }

    @Test
    @DisplayName("AC1 正例：OSS 对象回读存在 ⇒ 正常登记（服务端链，不接收客户端 ossId）")
    void ac1ExistingObjectRegisters() {
        when(ossService.upload(any(MultipartFile.class))).thenReturn(ossVo(OSS_ID, null));
        when(ossService.getById(OSS_ID)).thenReturn(ossVo(OSS_ID, "http://oss/p142.pdf"));

        Deliverable d = service.upload(100L, multipart("纪要.pdf", CONTENT.length, CONTENT), PM);

        verify(deliverableMapper).insert(d);
        assertThat(d.getOssId()).isEqualTo(OSS_ID);
        assertThat(d.getFileUrl()).isEqualTo("http://oss/p142.pdf");
    }

    // ==================== AC2 100MB 和格式策略 ====================

    @Test
    @DisplayName("AC2 反例：超过 100MB ⇒ 上传前拒绝，对象存储零触达")
    void ac2OversizeRejectedBeforeStorage() {
        long over = DeliverableService.MAX_UPLOAD_BYTES + 1;
        MultipartFile file = multipart("big.pdf", over, new byte[]{0});

        assertThatThrownBy(() -> service.upload(100L, file, PM))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("100MB");

        verifyNoInteractions(ossService);
        verify(deliverableMapper, never()).insert(any(Deliverable.class));
    }

    @Test
    @DisplayName("AC2 边界正例：恰好 100MB（≤ 语义）⇒ 放行到上传链")
    void ac2Exactly100MbPasses() {
        MultipartFile file = multipart("big.pdf", DeliverableService.MAX_UPLOAD_BYTES, CONTENT);
        when(ossService.upload(any(MultipartFile.class))).thenReturn(ossVo(OSS_ID, null));
        when(ossService.getById(OSS_ID)).thenReturn(ossVo(OSS_ID, "http://oss/big.pdf"));

        service.upload(100L, file, PM);

        verify(ossService).upload(file);
        verify(deliverableMapper).insert(any(Deliverable.class));
    }

    @Test
    @DisplayName("AC2 反例：格式策略白名单外（.exe）⇒ 拒绝，对象存储零触达")
    void ac2ForbiddenExtensionRejected() {
        MultipartFile file = multipart("payload.exe", CONTENT.length, CONTENT);

        assertThatThrownBy(() -> service.upload(100L, file, PM))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("格式");

        verifyNoInteractions(ossService);
    }

    @Test
    @DisplayName("AC2 正例：TS14 白名单 doc/xls/ppt/pdf/img/zip 系扩展名放行")
    void ac2WhitelistedExtensionsPass() {
        for (String name : List.of("a.doc", "b.docx", "c.xls", "d.xlsx", "e.ppt", "f.pptx",
            "g.pdf", "h.jpg", "i.PNG", "j.jpeg", "k.gif", "l.bmp", "m.webp", "n.zip")) {
            org.mockito.Mockito.reset(ossService);
            MultipartFile file = multipart(name, CONTENT.length, CONTENT);
            when(ossService.upload(any(MultipartFile.class))).thenReturn(ossVo(OSS_ID, null));
            when(ossService.getById(OSS_ID)).thenReturn(ossVo(OSS_ID, "http://oss/f"));
            service.upload(100L, file, PM);
            verify(ossService).upload(file);
        }
    }

    // ==================== AC3 上传者/大小/hash 入库 ====================

    @Test
    @DisplayName("AC3：uploadedBy=会话 actor.id、fileSize=实际字节、contentHash=SHA-256 hex(64)")
    void ac3UploaderSizeHashPersisted() {
        when(ossService.upload(any(MultipartFile.class))).thenReturn(ossVo(OSS_ID, null));
        when(ossService.getById(OSS_ID)).thenReturn(ossVo(OSS_ID, "http://oss/p.pdf"));

        service.upload(100L, multipart("设计文档.pdf", CONTENT.length, CONTENT), PM);

        ArgumentCaptor<Deliverable> captor = ArgumentCaptor.forClass(Deliverable.class);
        verify(deliverableMapper).insert(captor.capture());
        Deliverable d = captor.getValue();
        assertThat(d.getUploadedBy()).isEqualTo(7L);           // 会话身份，非请求体
        assertThat(d.getFileSize()).isEqualTo(CONTENT.length);  // 服务端实际字节
        assertThat(d.getContentHash()).isEqualTo(DigestUtil.sha256Hex(CONTENT)); // 同算法可复核
        assertThat(d.getContentHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(d.getUploadedAt()).isNotNull();
    }

    // ==================== AC4 下载校验项目归属 ====================

    private Deliverable stored(long projectId) {
        return Deliverable.builder().id(500L).actionId(100L).projectId(projectId)
            .fileName("纪要.pdf").ossId(OSS_ID).fileSize((long) CONTENT.length)
            .uploadedBy(7L).contentHash(DigestUtil.sha256Hex(CONTENT)).build();
    }

    @Test
    @DisplayName("AC4 反例：非项目在职成员下载 ⇒ FORBIDDEN(30001) fail-closed，OSS 零触达")
    void ac4NonMemberForbidden() throws Exception {
        when(deliverableMapper.selectById(500L)).thenReturn(stored(10L));
        when(projectMapper.selectById(10L)).thenReturn(new Project().setId(10L).setTenantId("000000"));
        when(projectMemberMapper.selectCount(any())).thenReturn(0L); // 无在职成员记录
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThatThrownBy(() -> service.download(500L, PM, response))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(t -> ((IpdBusinessException) t).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.FORBIDDEN);

        verify(ossService, never()).download(anyLong(), any());
    }

    @Test
    @DisplayName("AC4 正例：项目在职成员下载 ⇒ 放行至对象存储下载链")
    void ac4ActiveMemberAllowed() throws Exception {
        when(deliverableMapper.selectById(500L)).thenReturn(stored(10L));
        Project project = new Project();
        project.setId(10L);
        when(projectMapper.selectById(10L)).thenReturn(project);
        when(projectMemberMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1L);
        HttpServletResponse response = mock(HttpServletResponse.class);

        service.download(500L, PM, response);

        verify(ossService).download(OSS_ID, response);
    }

    @Test
    @DisplayName("AC4 正例：SUPER_ADMIN 运维豁免 ⇒ 放行（既有守卫 3 口径）")
    void ac4SuperAdminExempt() throws Exception {
        when(deliverableMapper.selectById(500L)).thenReturn(stored(10L));
        HttpServletResponse response = mock(HttpServletResponse.class);

        service.download(500L, ADMIN, response);

        verify(ossService).download(OSS_ID, response);
    }

    @Test
    @DisplayName("AC4 反例：未认证 actor ⇒ UNAUTHORIZED(20001)，资源零触达")
    void ac4UnauthenticatedRejected() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        assertThatThrownBy(() -> service.download(500L, null, response))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(t -> ((IpdBusinessException) t).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(deliverableMapper);
    }

    // ==================== AC5 失败孤儿补偿 ====================

    @Test
    @DisplayName("AC5 反例：登记 insert 失败 ⇒ 补偿删除已上传 OSS 对象且原始异常透传")
    void ac5OrphanCompensatedOnInsertFailure() {
        when(ossService.upload(any(MultipartFile.class))).thenReturn(ossVo(OSS_ID, null));
        when(ossService.getById(OSS_ID)).thenReturn(ossVo(OSS_ID, "http://oss/p.pdf"));
        RuntimeException dbFailure = new RuntimeException("forced insert failure");
        when(deliverableMapper.insert(any(Deliverable.class))).thenThrow(dbFailure);

        assertThatThrownBy(() -> service.upload(100L, multipart("纪要.pdf", CONTENT.length, CONTENT), PM))
            .isSameAs(dbFailure);

        verify(ossService).deleteWithValidByIds(eq(List.of(OSS_ID)), eq(false));
    }

    @Test
    @DisplayName("AC5 边界：补偿自身失败不掩盖原始异常（孤儿残留面交卡外巡检残余）")
    void ac5CompensationFailureDoesNotMaskOriginal() {
        when(ossService.upload(any(MultipartFile.class))).thenReturn(ossVo(OSS_ID, null));
        when(ossService.getById(OSS_ID)).thenReturn(ossVo(OSS_ID, "http://oss/p.pdf"));
        when(deliverableMapper.insert(any(Deliverable.class))).thenThrow(new IllegalStateException("db down"));
        when(ossService.deleteWithValidByIds(anyCollection(), eq(false))).thenThrow(new RuntimeException("storage unreachable"));

        assertThatThrownBy(() -> service.upload(100L, multipart("纪要.pdf", CONTENT.length, CONTENT), PM))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("db down");
    }

    // ==================== AC3 真库回环（非仅 Mock；ipd_dev 随机 ID + ROLLBACK + 零残留） ====================

    @Test
    @DisplayName("AC3 真库：ipd_dev.deliverables content_hash 列存在且 uploader/size/hash 真实往返（ROLLBACK 不留痕）")
    void ac3RealMysqlRoundTripWithContentHashColumn() throws Exception {
        Map<String, String> client = readClientConfig();
        String jdbcUrl = "jdbc:mysql://" + client.get("host") + ":" + client.get("port")
            + "/ipd_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectionTimeZone=Asia/Shanghai";
        Path jar = Path.of(System.getProperty("user.home"))
            .resolve(System.getProperty("ipd.p142.driverJar",
                ".m2/repository/com/mysql/mysql-connector-j/9.5.0/mysql-connector-j-9.5.0.jar"));
        assertThat(jar).as("本机 mysql-connector-j").exists();
        java.net.URLClassLoader loader = new java.net.URLClassLoader(
            new java.net.URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
        Driver driver = (Driver) loader.loadClass("com.mysql.cj.jdbc.Driver")
            .getDeclaredConstructor().newInstance();

        Properties props = new Properties();
        props.setProperty("user", client.get("user"));
        props.setProperty("password", client.get("password"));
        long randomId = 8_000_000_000_000_000L + Math.floorMod(new SecureRandom().nextLong(), 900_000_000_000_000L);
        String hash = DigestUtil.sha256Hex(CONTENT);
        List<Long> created = new ArrayList<>();
        created.add(randomId);
        try {
            try (Connection connection = driver.connect(jdbcUrl, props)) {
                connection.setAutoCommit(false);
                try {
                    // ① DDL 真实性：content_hash 列必须已在真库（2026-09-25 migration 产物）
                    try (PreparedStatement check = connection.prepareStatement(
                        "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE FROM information_schema.COLUMNS"
                            + " WHERE TABLE_SCHEMA='ipd_dev' AND TABLE_NAME='deliverables' AND COLUMN_NAME='content_hash'")) {
                        try (ResultSet rows = check.executeQuery()) {
                            assertThat(rows.next()).as("ipd_dev.deliverables.content_hash 列存在（migration 已 apply）").isTrue();
                            assertThat(rows.getString("DATA_TYPE")).isEqualTo("varchar");
                            assertThat(rows.getLong("CHARACTER_MAXIMUM_LENGTH")).isEqualTo(64L);
                            assertThat(rows.getString("IS_NULLABLE")).isEqualTo("YES");
                        }
                    }
                    // ② AC3 真实落库回环：上传者/大小/hash 写入后原样读回
                    String insert = "INSERT INTO deliverables (id,action_id,project_id,file_name,oss_id,file_size,uploaded_by,content_hash,tenant_id,del_flag)"
                        + " VALUES (?,?,?,'P142真库回环.pdf',?,?,?,?, '0', '0')";
                    try (PreparedStatement statement = connection.prepareStatement(insert)) {
                        statement.setLong(1, randomId);
                        statement.setLong(2, 100L);
                        statement.setLong(3, 10L);
                        statement.setLong(4, OSS_ID);
                        statement.setLong(5, CONTENT.length);
                        statement.setLong(6, PM.id());
                        statement.setString(7, hash);
                        assertThat(statement.executeUpdate()).isEqualTo(1);
                    }
                    try (PreparedStatement select = connection.prepareStatement(
                        "SELECT uploaded_by, file_size, content_hash FROM deliverables WHERE id=?")) {
                        select.setLong(1, randomId);
                        try (ResultSet rows = select.executeQuery()) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getLong("uploaded_by")).isEqualTo(7L);
                            assertThat(rows.getLong("file_size")).isEqualTo(CONTENT.length);
                            assertThat(rows.getString("content_hash")).isEqualTo(hash);
                        }
                    }
                } finally {
                    connection.rollback();
                }
            }
            // ③ 独立连接确认夹具零残留（不污染真库既有 2 行）
            try (Connection verifier = driver.connect(jdbcUrl, props);
                 PreparedStatement count = verifier.prepareStatement("SELECT COUNT(*) FROM deliverables WHERE id=?")) {
                count.setLong(1, created.get(0));
                try (ResultSet rows = count.executeQuery()) {
                    rows.next();
                    assertThat(rows.getInt(1)).as("ROLLBACK 后独立连接零残留").isZero();
                }
            }
        } finally {
            loader.close();
        }
    }

    /** 读取本机隔离环境 mysql-app.cnf（key=value）；缺失即显式 abort，不假绿。 */
    private static Map<String, String> readClientConfig() throws Exception {
        Path cnf = Path.of(System.getProperty("ipd.p142.clientConfig",
            "/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-app.cnf"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(cnf),
            "本机 ipd_dev 隔离配置缺失（" + cnf + "），真库回环用例跳过——非本卡通过证据");
        Map<String, String> client = new HashMap<>();
        for (String line : Files.readAllLines(cnf)) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                client.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue("127.0.0.1".equals(client.get("host")), "cnf host 非本机，跳过真库");
        return client;
    }
}
