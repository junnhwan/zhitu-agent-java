package com.zhituagent.llm;

import com.zhituagent.config.LlmProperties;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRateLimiterTest {

    @Test
    void shouldNoOpWhenDisabled() {
        LlmProperties.RateLimit config = new LlmProperties.RateLimit();
        config.setEnabled(false);
        LlmRateLimiter limiter = new LlmRateLimiter(config, null);

        assertThat(limiter.isEnabled()).isFalse();
        // 多次 acquire 都不会阻塞或抛异常
        for (int i = 0; i < 100; i++) {
            limiter.acquire("test");
        }
    }

    @Test
    void shouldAllowCallsWithinPeriod() {
        LlmProperties.RateLimit config = new LlmProperties.RateLimit();
        config.setEnabled(true);
        config.setLimitForPeriod(5);
        config.setLimitRefreshPeriodSeconds(60);
        config.setTimeoutSeconds(2);
        LlmRateLimiter limiter = new LlmRateLimiter(config, null);

        AtomicInteger counter = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            limiter.acquire("test");
            counter.incrementAndGet();
        }
        assertThat(counter.get()).isEqualTo(5);
    }

    @Test
    void shouldThrowWhenExceedingPeriodWithShortTimeout() {
        LlmProperties.RateLimit config = new LlmProperties.RateLimit();
        config.setEnabled(true);
        config.setLimitForPeriod(2);
        config.setLimitRefreshPeriodSeconds(60);
        config.setTimeoutSeconds(0); // 不等待,立即抛
        LlmRateLimiter limiter = new LlmRateLimiter(config, null);

        limiter.acquire("test");
        limiter.acquire("test");
        // 第三次超出 limitForPeriod 且 timeout=0,应抛 RequestNotPermitted
        assertThatThrownBy(() -> limiter.acquire("test"))
                .isInstanceOf(RequestNotPermitted.class);
    }

    @Test
    void disabledFactoryShouldReturnNoOp() {
        LlmRateLimiter limiter = LlmRateLimiter.disabled();
        assertThat(limiter.isEnabled()).isFalse();
        limiter.acquire("test"); // 不抛
    }
}
