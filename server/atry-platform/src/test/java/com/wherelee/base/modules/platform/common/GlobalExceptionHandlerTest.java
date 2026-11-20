package com.wherelee.base.modules.platform.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理器测试。
 *
 * <p>不变式：业务异常保留 HTTP 状态与可读消息；参数校验取第一条字段错误消息；
 * 未知异常 500 且**不泄露**内部异常信息（防止堆栈/SQL 细节外泄）。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 业务异常原样映射状态与消息() {
        ResponseEntity<ErrorResponse> response =
                handler.handleBiz(BizException.tooManyRequests("请求过于频繁，请稍后再试"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("请求过于频繁，请稍后再试");

        assertThat(handler.handleBiz(BizException.notFound("不存在")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handleBiz(BizException.unauthorized("请先登录")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.handleBiz(BizException.badRequest("参数错误")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 参数校验取第一条字段错误消息() {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "username", "用户名不能为空"));
        bindingResult.addError(new FieldError("request", "password", "密码不能为空"));
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(exception);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("用户名不能为空");
    }

    @Test
    void 参数校验无字段错误时给兜底消息() {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "request");
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(null, bindingResult);

        assertThat(handler.handleValidation(exception).getBody().message())
                .isEqualTo("请求参数不正确");
    }

    @Test
    void 静态资源不存在返回404() {
        NoResourceFoundException exception = new NoResourceFoundException(HttpMethod.GET, "/missing.js", "missing.js");
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(exception);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("请求的资源不存在");
    }

    @Test
    void 未知异常返回500且不泄露内部信息() {
        ResponseEntity<ErrorResponse> response =
                handler.handleUnexpected(new RuntimeException("jdbc: connection refused secret-token"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message())
                .isEqualTo("服务暂时不可用，请稍后再试")
                .doesNotContain("secret-token", "jdbc", "Exception");
    }
}
