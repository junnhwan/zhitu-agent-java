package com.zhituagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ErrorMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public ErrorMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public static ErrorMetricsRecorder noop() {
        return new ErrorMetricsRecorder(null);
    }

    public void recordError(String category, String code, int status) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("zhitu_api_errors_total")
                .tag("category", safe(category))
                .tag("code", safe(code))
                .tag("status", Integer.toString(Math.max(0, status)))
                .register(meterRegistry)
                .increment();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
