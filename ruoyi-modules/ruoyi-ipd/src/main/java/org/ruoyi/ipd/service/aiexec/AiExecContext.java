package org.ruoyi.ipd.service.aiexec;

import org.ruoyi.ipd.security.IpdActor;

import java.time.Clock;

/**
 * R221 执行上下文（spec §4.2）：系统身份（AI_SYSTEM_PERSON_ID=0）+ 可注入时钟（退避/审计时间戳确定性）。
 * 注：IpdActor 实名在 org.ruoyi.ipd.security（照 GateSignScanScheduler import 对齐）。
 */
public record AiExecContext(IpdActor systemActor, Clock clock) {
}
