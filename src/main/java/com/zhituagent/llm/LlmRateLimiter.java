package com.zhituagent.llm;

import com.zhituagent.config.LlmProperties;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class LlmRateLimiter {

    public static final String LIMITER_NAME = "llm";
    private static final Logger log = LoggerFactory.getLogger(LlmRateLimiter.class);

    private final boolean enabled;
    private final RateLimiter rateLimiter;
    private final long timeoutSeconds;

    @Autowired
    public LlmRateLimiter(LlmProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
        this(properties.getRateLimit(), meterRegistry == null ? null : meterRegistry.getIfAvailable());
    }

    LlmRateLimiter(LlmProperties.RateLimit config, MeterRegistry meterRegistry) {
        this.enabled = config != null && config.isEnabled();
        if (!enabled) {
            this.rateLimiter = null;
            this.timeoutSeconds = 0L;
            log.info("llm rate limiter disabled (zhitu.llm.rate-limit.enabled=false)");
            return;
        }
        RateLimiterConfig limiterConfig = RateLimiterConfig.custom()
                .limitForPeriod(config.getLimitForPeriod())
                .limitRefreshPeriod(Duration.ofSeconds(config.getLimitRefreshPeriodSeconds()))
                .timeoutDuration(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();
        RateLimiterRegistry registry = RateLimiterRegistry.of(limiterConfig);
        this.rateLimiter = registry.rateLimiter(LIMITER_NAME);
        this.timeoutSeconds = config.getTimeoutSeconds();
        if (meterRegistry != null) {
            TaggedRateLimiterMetrics.ofRateLimiterRegistry(registry).bindTo(meterRegistry);
        }
        log.info(
                "llm rate limiter enabled limitForPeriod={} refreshPeriodSec={} timeoutSec={}",
                config.getLimitForPeriod(),
                config.getLimitRefreshPeriodSeconds(),
                config.getTimeoutSeconds()
        );
    }

    public static LlmRateLimiter disabled() {
        LlmProperties.RateLimit disabled = new LlmProperties.RateLimit();
        disabled.setEnabled(false);
        return new LlmRateLimiter(disabled, null);
    }

    public void acquire(String operation) {
        if (!enabled) {
            return;
        }
        try {
            RateLimiter.waitForPermission(rateLimiter);
        } catch (RequestNotPermitted exception) {
            log.warn(
                    "llm rate limiter rejected operation={} after timeoutSec={}",
                    operation,
                    timeoutSeconds
            );
            throw exception;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
