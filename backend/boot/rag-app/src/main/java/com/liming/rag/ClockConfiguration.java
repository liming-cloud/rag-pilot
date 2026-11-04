package com.liming.rag;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全系统的时间来源（#136）：各模块取“现在”“今天”都注入这个 Clock，不直接调用 {@code now()}，测试可以固定时间。
 * 用 UTC；按天统计、资料有效期的“今天”由各处换成部署的统计时区（D-045）。
 */
@Configuration
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
