package org.ruoyi.chat.kernel;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Logging-framework extension for the enabled official AgentTraceMiddleware. */
public final class OfficialAgentTraceLogging {
    public static final String NATIVE_LOGGER = "io.agentscope.harness.agent.middleware.AgentTraceMiddleware";
    public static final String SAFE_LOGGER = "org.ruoyi.chat.kernel.OfficialAgentTrace";
    private static final String APPENDER = "OFFICIAL_AGENT_TRACE_METADATA";
    private OfficialAgentTraceLogging() { }

    /** Install before building agents; only the official trace logger is routed through redaction. */
    public static void install() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            throw new IllegalStateException("Official trace requires the supported logging extension");
        }
        install(context);
    }

    static void install(LoggerContext context) {
        synchronized (context) {
            Logger nativeLogger = context.getLogger(NATIVE_LOGGER);
            if (nativeLogger.getAppender(APPENDER) instanceof MetadataAppender existing && existing.isStarted()) {
                return;
            }
            List<Appender<ILoggingEvent>> destinations = new ArrayList<>();
            nativeLogger.iteratorForAppenders().forEachRemaining(destinations::add);
            // Existing trace-specific destinations also receive sanitized events, never raw text.
            for (var destination : destinations) { nativeLogger.detachAppender(destination); }
            var appender = new MetadataAppender(context.getLogger(SAFE_LOGGER), destinations);
            appender.setContext(context);
            appender.setName(APPENDER);
            appender.start();
            nativeLogger.setAdditive(false);
            nativeLogger.addAppender(appender);
        }
    }

    static final class MetadataAppender extends AppenderBase<ILoggingEvent> {
        private final Logger safeLogger;
        private final List<Appender<ILoggingEvent>> destinations;
        private final Map<String, Long> started = new ConcurrentHashMap<>();
        MetadataAppender(Logger safeLogger, List<Appender<ILoggingEvent>> destinations) {
            this.safeLogger = safeLogger;
            this.destinations = List.copyOf(destinations);
        }

        @Override protected void append(ILoggingEvent event) {
            String phase = phase(event.getMessage());
            Object[] arguments = event.getArgumentArray();
            String actor = fingerprint(arguments != null && arguments.length > 0 ? arguments[0] : null);
            long timestamp = event.getTimeStamp();
            if ("PRE_CALL".equals(phase)) { started.put(actor, timestamp); }
            Long start = started.get(actor);
            long elapsed = start == null ? 0L : Math.max(0L, timestamp - start);
            if ("POST_CALL".equals(phase) || "ERROR".equals(phase)) { started.remove(actor); }
            var safe = new LoggingEvent();
            safe.setLoggerName(SAFE_LOGGER);
            safe.setLevel(event.getLevel());
            safe.setTimeStamp(timestamp);
            safe.setThreadName("official-agent-trace");
            safe.setMDCPropertyMap(Map.of());
            safe.setMessage("official_agent_trace phase={} actorFingerprint={} argumentCount={} elapsedMillis={}");
            safe.setArgumentArray(new Object[] {phase, actor, arguments == null ? 0 : arguments.length, elapsed});
            for (var destination : destinations) { destination.doAppend(safe); }
            safeLogger.callAppenders(safe);
        }

        private static String phase(String template) {
            if (template != null) {
                for (String known : List.of("PRE_CALL", "POST_CALL", "PRE_REASONING", "POST_REASONING", "PRE_ACTING", "POST_ACTING", "ERROR")) {
                    if (template.contains(known)) { return known; }
                }
            }
            return "OTHER";
        }

        private static String fingerprint(Object actor) {
            try {
                // Hash only scalar actor labels; never invoke arbitrary payload toString methods.
                String label = actor instanceof String value ? value : "unknown";
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(label.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            } catch (java.security.NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 unavailable", failure);
            }
        }
    }
}
