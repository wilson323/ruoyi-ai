package org.ruoyi.ipd.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产配置 fail-fast 启动接线：把 {@link ProdConfigFailFastValidator} 的校验结论变成启动决策。
 *
 * <p><b>接线规则</b>（判定依据见 {@link ProdConfigFailFastValidator} 类注释）：
 * <ul>
 *   <li>只在 prod profile 生效（{@code @Profile("prod")}）——dev/local 由 application-dev.yml
 *       提供 devOnly 兜底值，本地开发不该被生产门槛卡住；</li>
 *   <li>致命档非空 → 抛 {@link IllegalStateException} 拒绝启动，异常消息即
 *       {@link ProdConfigFailFastValidator#render} 的完整清单（哪个变量没设 → 什么后果 → 怎么修），
 *       运维照着 export 即可；</li>
 *   <li>仅警告档 → 打醒目告警后放行：服务能活，但要把「哪个功能因此不可用」喊到日志里，
 *       不许静默降级。</li>
 * </ul>
 *
 * <p>启动时序：本类是 {@link ApplicationRunner}，在容器就绪后、开始对外服务前执行；
 * 抛异常会让 Spring Boot 启动流程失败退出，等效于拒绝启动。
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProdConfigFailFastRunner implements ApplicationRunner {

    private final Environment environment;
    private final ProdConfigFailFastValidator validator;

    @Override
    public void run(ApplicationArguments args) {
        ProdConfigFailFastValidator.Report report = validator.inspect(environment);

        if (report.failed()) {
            throw new IllegalStateException("生产环境必需配置校验失败，已拒绝启动（prod profile）。"
                    + ProdConfigFailFastValidator.render(report));
        }

        if (!report.warning().isEmpty()) {
            log.warn("[IPD][ProdConfig] 生产配置存在 {} 项警告：不阻断启动，但以下功能不可用——", report.warning().size());
            for (ProdConfigFailFastValidator.Finding finding : report.warning()) {
                log.warn("[IPD][ProdConfig] [!] {} 未设置（配置键: {}）→ {}",
                        finding.envVar(), finding.configKey(), finding.consequence());
            }
            return;
        }

        log.info("[IPD][ProdConfig] 生产环境必需配置校验通过（0 致命 / 0 警告）");
    }
}
