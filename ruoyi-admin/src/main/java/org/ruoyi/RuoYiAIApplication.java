package org.ruoyi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动程序
 *
 * @author Lion Li
 */
@SpringBootApplication
// R24 治理轮：启用调度（HandoverOverdueScanner 09:05 + PersonResignEscalator 09:00）。
// 此前 HandoverOverdueScanner/PersonResignEscalator 的 @Scheduled 注解被 Spring 静默忽略（OPS-04 卡依赖）；
// 启用后两调度器同日起效，无其它副作用（未配 @Scheduled 的 bean 不受影响）。
@EnableScheduling
public class RuoYiAIApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(RuoYiAIApplication.class);
        application.setApplicationStartup(new BufferingApplicationStartup(2048));
        application.run(args);
        System.out.println("(♥◠‿◠)ﾉﾞ     RuoYi-AI启动成功   ლ(´ڡ`ლ)");
    }

}
