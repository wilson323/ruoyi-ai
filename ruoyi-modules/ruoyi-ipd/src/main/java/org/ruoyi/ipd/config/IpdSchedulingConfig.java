package org.ruoyi.ipd.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OPS-04：IPD 侧调度启用开关。
 *
 * <p>背景：主应用未启用 Spring 调度——ruoyi-admin 不依赖 ruoyi-common-job，
 * {@code SnailJobConfig} 的 @EnableScheduling 因依赖缺失 + snail-job.enabled=false 双重不生效，
 * 导致 IPD 的 @Scheduled 任务（离职升级 09:00 / 移交超时 09:05，错峰 5 分钟）静默不跑。
 * 本配置类显式开启后双 job 生效；后续新增调度任务须在此登记错峰时刻。
 */
@Configuration
@EnableScheduling
public class IpdSchedulingConfig {
}
