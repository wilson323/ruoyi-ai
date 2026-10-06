package org.ruoyi.ipd.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @Primary 基础 bean 装配（2026-10-02 迁移自已下线的 ruoyi-aiflow BeanConfig）。
 *
 * <p>ruoyi-aiflow / ruoyi-workflow 整模块下线时，其 BeanConfig 提供的两个仍有存活消费者
 * 的 bean 随模块消失：mainExecutor 消费者（{@code LegacyImportService}）将在启动期抛
 * NoSuchBeanDefinitionException；objectMapper 的 10+ 处注入点会静默回落 Spring Boot 默认
 * mapper，丢失 Long→字符串、NON_NULL、LocalDateTime {@code yyyy-MM-dd HH:mm:ss} 契约
 * （基线 ruoyi-common-json 的 JacksonConfig 只定制默认 mapper 的 Long→BigNumberSerializer，
 * 非 @Primary，无法承接）。本类按删除前行为原样恢复，参数零改动。
 */
@Slf4j
@Configuration
public class IpdPrimaryBeansConfig {

    /**
     * Long→字符串、LocalDateTime→yyyy-MM-dd HH:mm:ss、null 字段不序列化。
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        log.info("Configuration:create objectMapper (migrated from retired ruoyi-aiflow BeanConfig)");
        ObjectMapper objectMapper = new Jackson2ObjectMapperBuilder().createXmlMapper(false).build();
        SimpleModule ipdTimeModule = new SimpleModule();
        ipdTimeModule.addSerializer(Long.class, ToStringSerializer.instance);
        ipdTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer());
        ipdTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer());
        // LocalDate 缺省走 JavaTimeModule 时间戳数组（如 [2026,10,1]），前端按字符串契约
        // 渲染为空（2026-10-06 D9 复验实测 KpiRawRecord.recordPeriod / RecoveryWarning.warningDate /
        // LandedScenario.landedDate 三处实体字段均受影响），统一 yyyy-MM-dd 字符串。
        ipdTimeModule.addSerializer(LocalDate.class, new LocalDateSerializer());
        objectMapper.registerModules(ipdTimeModule, new JavaTimeModule(), new Jdk8Module());
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return objectMapper;
    }

    /**
     * 并行导入执行器（core=min(2×CPU,100)、max=100，与原 aiflow 定义一致）。
     */
    @Bean(name = "mainExecutor")
    @Primary
    public AsyncTaskExecutor mainExecutor() {
        int processorsNum = Runtime.getRuntime().availableProcessors();
        log.info("mainExecutor,processorsNum:{}", processorsNum);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数不得超过最大线程数
        int maxPoolSize = 100;
        executor.setCorePoolSize(Math.min(processorsNum * 2, maxPoolSize));
        executor.setMaxPoolSize(maxPoolSize);
        return executor;
    }

    /**
     * LocalDateTime ↔ yyyy-MM-dd HH:mm:ss（原 org.ruoyi.workflow.util 序列化器内联迁移）。
     */
    static class LocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
    }

    static class LocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            return LocalDateTime.parse(p.getValueAsString(),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }

    /**
     * LocalDate → yyyy-MM-dd（否则 JavaTimeModule 默认序列化为 [y,M,d] 数组）。
     */
    static class LocalDateSerializer extends JsonSerializer<LocalDate> {

        @Override
        public void serialize(LocalDate value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
    }
}
