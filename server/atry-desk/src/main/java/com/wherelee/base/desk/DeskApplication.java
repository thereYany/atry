package com.wherelee.base.desk;

import com.wherelee.base.app.alert.AlertProperties;
import com.wherelee.base.bootstrap.KernelSeedProperties;
import com.wherelee.base.desk.config.DeskProperties;
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.provider.deepseek.DeepSeekProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 客服垂直宿主。
 *
 * <p>只装配内核能力（platform starter + identity + payment + llm + metering）与本垂直的
 * {@code com.wherelee.base.desk} 业务包；业务表由本模块自带 Flyway 迁移维护。
 * 内核能力一律通过 {@code atry-contracts} 调用，不在垂直域内复制。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.wherelee.base.bootstrap",
        "com.wherelee.base.modules.identity",
        "com.wherelee.base.modules.payment",
        "com.wherelee.base.modules.llm",
        "com.wherelee.base.modules.metering",
        "com.wherelee.base.desk"
})
@EnableConfigurationProperties({KernelSeedProperties.class, AlertProperties.class, LlmProperties.class,
        DeepSeekProperties.class, DeskProperties.class})
@EnableScheduling
public class DeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeskApplication.class, args);
    }
}
