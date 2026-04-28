package com.zhituagent.common.error;

import com.zhituagent.api.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleApiException(ApiException exception, HttpServletRequest request) {
        log.warn(
                "业务异常 api.error code={} requestId={} path={} message={}",
                exception.getErrorCode().name(),
                request.getAttribute("requestId"),
                request.getRequestURI(),
                exception.getMessage()
        );
        return new ApiErrorResponse(
                exception.getErrorCode().name(),
                exception.getMessage(),
                (String) request.getAttribute("requestId")
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleValidationException(MethodArgumentNotValidException exception,
                                                      HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("validation failed");
        log.warn(
                "参数校验异常 api.validation_error requestId={} path={} message={}",
                request.getAttribute("requestId"),
                request.getRequestURI(),
                message
        );
        return new ApiErrorResponse(ErrorCode.INVALID_ARGUMENT.name(), message, (String) request.getAttribute("requestId"));
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiErrorResponse handleUnexpectedException(Exception exception, HttpServletRequest request) {
        log.error(
                "未预期异常 api.unexpected_error requestId={} path={} message={}",
                request.getAttribute("requestId"),
                request.getRequestURI(),
                exception.getMessage(),
                exception
        );
        return new ApiErrorResponse(ErrorCode.INTERNAL_ERROR.name(), exception.getMessage(), (String) request.getAttribute("requestId"));
    }
}
