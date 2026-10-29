package com.toonflow.server.common;

import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> business(BusinessException exception) {
        return response(exception.getStatus(), exception.getData(), exception.getMessage());
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Object>> validation(BindException exception) {
        List<Map<String, Object>> issues = exception.getFieldErrors().stream()
                .map(field -> Map.<String, Object>of("code", "custom", "path", List.of(field.getField()),
                        "message", "字段校验失败"))
                .toList();
        return response(400, issues, "参数错误");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class,
            IllegalArgumentException.class, InvalidPathException.class})
    public ResponseEntity<ApiResponse<Object>> badRequest(Exception exception) {
        return response(400, null, "参数错误");
    }

    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    public ResponseEntity<ApiResponse<Object>> forbidden(Exception exception) {
        return response(403, Map.of("code", "EACCES"), "无权访问该路径");
    }

    @ExceptionHandler({NoSuchFileException.class, NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Object>> notFound(Exception exception) {
        return response(404, Map.of("code", "ENOENT"), "目标不存在");
    }

    @ExceptionHandler(NotDirectoryException.class)
    public ResponseEntity<ApiResponse<Object>> notDirectory(NotDirectoryException exception) {
        return response(404, Map.of("code", "ENOTDIR"), "目标不是目录");
    }

    @ExceptionHandler({FileAlreadyExistsException.class, DuplicateKeyException.class})
    public ResponseEntity<ApiResponse<Object>> exists(Exception exception) {
        return response(409, Map.of("code", "EEXIST"), "目标已存在");
    }

    @ExceptionHandler(DirectoryNotEmptyException.class)
    public ResponseEntity<ApiResponse<Object>> notEmpty(DirectoryNotEmptyException exception) {
        return response(409, Map.of("code", "ENOTEMPTY"), "目录非空");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> tooLarge(MaxUploadSizeExceededException exception) {
        return response(413, null, "文件超过体积上限");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> unsupported(HttpMediaTypeNotSupportedException exception) {
        return response(415, null, "不支持的媒体类型");
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Object>> io(IOException exception) {
        LOG.error("event=file_operation_failed errorType={}", exception.getClass().getSimpleName());
        return response(500, null, "文件操作失败");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> unexpected(Exception exception) {
        // 不输出异常 message / 堆栈：第三方错误可能包含 SQL 凭据或供应商密钥。
        LOG.error("event=request_failed errorType={}", exception.getClass().getSimpleName());
        return response(500, null, "服务端内部错误");
    }

    private ResponseEntity<ApiResponse<Object>> response(int status, Object data, String message) {
        return ResponseEntity.status(status).body(new ApiResponse<>(status, data, message));
    }
}
