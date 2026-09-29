package org.ruoyi.ipd.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.advice.IpdServiceExceptionAdvice;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditChainHead;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.mapper.AuditChainHeadMapper;
import org.ruoyi.ipd.mapper.AuditLogMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Date;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OPS-06 审计 trace 接线四断点回归（runbook ops06-只读监测与报警.md「审计」行解除条件）：
 * <ol>
 *   <li>断点① audit_logs 增 trace_id 定位列（DDL 见 2026-09-27-ipd-audit-trace-id.sql）；</li>
 *   <li>断点② AuditLogServiceImpl.append 写入时刻抓 MDC traceId（不入 canonical 哈希）；</li>
 *   <li>断点③ IpdServiceExceptionAdvice 复用请求入口 trace（不再每 handler 另铸 UUID）；</li>
 *   <li>断点④ 「[IPD] 未捕获异常 traceId=」marker 发射点（此前全仓无发射点）。</li>
 * </ol>
 *
 * <p>其中 {@code appendFailureEmitsMonitorMarkerBlock} 即 runbook 要求的「隔离环境注入审计故障
 * 验证」：确定性注入追加失败，断言 marker 块 + trace + AuditLogService 堆栈帧齐备——
 * ops06-monitor.py 的 scan_audit_log 契约（traceId= 后随空格、32 位无连字符 UUID、100 行窗口内
 * AuditLogService 帧）在 Java 侧被锁死，不会退化回 AUDIT_TRACE_NOT_WIRED。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditTraceWiringTest {

    private static final String MDC_TRACE = "0123456789abcdef0123456789abcdef";
    private static final String CALLER_TRACE = "fedcba9876543210fedcba9876543210";
    /** runbook 监测器 traceId= 正则（32 位无连字符或带连字符 UUID，后随空格/行尾）。 */
    private static final Pattern MONITOR_TRACE =
        Pattern.compile("traceId=([0-9a-fA-F]{32}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})(?=\\s|$)");

    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private AuditChainHeadMapper chainHeadMapper;
    @Mock
    private PersonMapper personMapper;

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (appender != null) {
            ((Logger) LoggerFactory.getLogger(AuditLogServiceImpl.class)).detachAppender(appender);
            ((Logger) LoggerFactory.getLogger(IpdServiceExceptionAdvice.class)).detachAppender(appender);
            appender = null;
        }
    }

    private AuditLogServiceImpl service() {
        return new AuditLogServiceImpl(auditLogMapper, chainHeadMapper, personMapper);
    }

    private static AuditLog draft() {
        return AuditLog.builder()
            .operatorId(0L)
            .action("CREATE")
            .entityType("test_entity")
            .entityId(1L)
            .createTime(new Date(1_700_000_000_000L))
            .build();
    }

    private void stubChain(long nextSeq) {
        AuditChainHead head = new AuditChainHead();
        head.setChainKey("GLOBAL");
        head.setNextSeq(nextSeq);
        head.setLastHash(null);
        when(chainHeadMapper.selectForUpdate("GLOBAL")).thenReturn(head);
        when(chainHeadMapper.advance(anyString(), anyLong(), anyString(), anyLong())).thenReturn(1);
    }

    private void captureLogs(Class<?> type) {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
    }

    @Test
    @DisplayName("断点②：append 写入时刻抓 MDC traceId 入行（trace_id 列），且不入 canonical 哈希")
    void appendCapturesMdcTraceAndKeepsHashProtocol() {
        MDC.put("traceId", MDC_TRACE);
        stubChain(7L);

        AuditLog first = service().append(draft());
        AuditLog preset = draft();
        preset.setTraceId(CALLER_TRACE);
        AuditLog third = service().append(preset);

        assertThat(first.getTraceId()).isEqualTo(MDC_TRACE);
        // 调用方已带 traceId 则尊重（隔离测试/补偿写入），不被 MDC 覆盖
        assertThat(third.getTraceId()).isEqualTo(CALLER_TRACE);
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, org.mockito.Mockito.atLeast(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(row -> assertThat(row.getTraceId()).isNotBlank());
        // 哈希协议红线：仅 traceId 不同的两行 curr_hash 必须一致（canonical 字段清单冻结）
        assertThat(first.getCurrHash()).isEqualTo(third.getCurrHash());
    }

    @Test
    @DisplayName("断点②：无 MDC（调度扫描/独立线程）时 trace_id 置 null，不造假日志链路")
    void appendWithoutMdcLeavesTraceNull() {
        MDC.clear();
        stubChain(7L);
        AuditLog row = service().append(draft());
        assertThat(row.getTraceId()).isNull();
    }

    @Test
    @DisplayName("隔离注入审计故障：追加失败发射 [IPD] 未捕获异常 traceId= 块 + AuditLogService 堆栈帧（runbook 解除条件）")
    void appendFailureEmitsMonitorMarkerBlock() {
        MDC.put("traceId", MDC_TRACE);
        when(chainHeadMapper.selectForUpdate("GLOBAL")).thenReturn(null); // 锚行缺失 = 确定性故障注入
        captureLogs(AuditLogServiceImpl.class);

        assertThatThrownBy(() -> service().append(draft()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        String text = event.getFormattedMessage();
        // marker 契约：[IPD] 未捕获异常 traceId=<32hex> 后随空格（监测器正则）+ 审计失败文案
        assertThat(text).contains("[IPD] 未捕获异常 traceId=" + MDC_TRACE + " 审计追加失败");
        assertThat(MONITOR_TRACE.matcher(text).find()).isTrue();
        // 100 行窗口判据：堆栈含 org.ruoyi.ipd.service.AuditLogService 帧（monitor 第二条件）
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getStackTraceElementProxyArray())
            .extracting(frame -> frame.getStackTraceElement().getClassName())
            .anyMatch(cls -> cls.startsWith("org.ruoyi.ipd.service.AuditLogService"));
    }

    @Test
    @DisplayName("断点③：advice 复用请求入口 trace（响应包络 == MDC 注入值），且不清他人 MDC")
    void adviceReusesRequestTrace() {
        MDC.put("traceId", MDC_TRACE);
        IpdServiceExceptionAdvice advice = new IpdServiceExceptionAdvice();

        ApiV1Response<Void> body =
            advice.handleIpdBusiness(new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "坏参")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getTraceId()).isEqualTo(MDC_TRACE);
        // owned=false：请求入口注入的 trace 由 TraceIdFilter 出口回收，advice 不得中途掐断
        assertThat(MDC.get("traceId")).isEqualTo(MDC_TRACE);
    }

    @Test
    @DisplayName("断点④：handleUnexpected 发射 [IPD] 未捕获异常 traceId= 块（filter 未触发时自铸 32hex 并回收）")
    void unexpectedEmitsMonitorMarkerBlock() {
        MDC.clear();
        captureLogs(IpdServiceExceptionAdvice.class);
        IpdServiceExceptionAdvice advice = new IpdServiceExceptionAdvice();

        ApiV1Response<Void> body = advice.handleUnexpected(new RuntimeException("boom")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getTraceId()).matches("[0-9a-f]{32}");
        assertThat(appender.list).hasSize(1);
        String text = appender.list.get(0).getFormattedMessage();
        assertThat(text).contains("[IPD] 未捕获异常 traceId=" + body.getTraceId() + " ");
        assertThat(MONITOR_TRACE.matcher(text).find()).isTrue();
        // owned=true：自铸值必须回收，防线程复用把上一请求 trace 泄给下一请求
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("断点③：ServiceException 映射路径同样走 trace 复用（日志/响应同源）")
    void serviceExceptionPathReusesTrace() {
        MDC.put("traceId", MDC_TRACE);
        IpdServiceExceptionAdvice advice = new IpdServiceExceptionAdvice();

        ApiV1Response<Void> body =
            advice.handleServiceException(new ServiceException("审计导出行数超限")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getTraceId()).isEqualTo(MDC_TRACE);
    }
}
