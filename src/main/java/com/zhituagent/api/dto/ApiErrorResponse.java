package com.zhituagent.api.dto;

public record ApiErrorResponse(
        String code,
        String message,
        String requestId,
        String category
) {
}
