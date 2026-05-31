package com.econo.auth.api.exception;

import com.econo.auth.member.exception.InvalidPasswordPolicyException;
import com.econo.auth.member.exception.MemberAlreadyExistsException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 전역 예외 핸들러 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/** API 에러 응답 */
	public record ApiError(
			String errorCode, String message, LocalDateTime timestamp, List<FieldError> fieldErrors) {

		public ApiError(String errorCode, String message) {
			this(errorCode, message, LocalDateTime.now(), null);
		}

		public ApiError(String errorCode, String message, List<FieldError> fieldErrors) {
			this(errorCode, message, LocalDateTime.now(), fieldErrors);
		}
	}

	/** 필드 에러 */
	public record FieldError(String field, String message) {}

	/**
	 * Bean Validation 오류 처리
	 *
	 * @param ex 예외
	 * @return 400 VALIDATION_FAILED
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
		List<FieldError> fieldErrors =
				ex.getBindingResult().getFieldErrors().stream()
						.map(e -> new FieldError(e.getField(), e.getDefaultMessage()))
						.collect(Collectors.toList());
		return ResponseEntity.badRequest()
				.body(new ApiError("VALIDATION_FAILED", "요청 값이 올바르지 않습니다.", fieldErrors));
	}

	/**
	 * loginId 중복 예외 처리
	 *
	 * @param ex 예외
	 * @return 409 MEMBER_ALREADY_EXISTS
	 */
	@ExceptionHandler(MemberAlreadyExistsException.class)
	public ResponseEntity<ApiError> handleMemberAlreadyExists(MemberAlreadyExistsException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ApiError("MEMBER_ALREADY_EXISTS", "이미 사용 중인 아이디입니다."));
	}

	/**
	 * 비밀번호 정책 위반 예외 처리
	 *
	 * @param ex 예외
	 * @return 400 INVALID_PASSWORD_POLICY
	 */
	@ExceptionHandler(InvalidPasswordPolicyException.class)
	public ResponseEntity<ApiError> handleInvalidPasswordPolicy(InvalidPasswordPolicyException ex) {
		return ResponseEntity.badRequest()
				.body(new ApiError("INVALID_PASSWORD_POLICY", "비밀번호는 대문자·소문자·숫자·특수기호를 각 1자 이상 포함해야 합니다."));
	}

	/**
	 * 잘못된 인자 예외 처리 — SignupService.validateLoginId() 등에서 발생
	 *
	 * @param ex 예외
	 * @return 400 INVALID_LOGIN_ID_FORMAT
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiError("INVALID_LOGIN_ID_FORMAT", ex.getMessage(), LocalDateTime.now(), null));
	}

	/**
	 * Spring MVC ResponseStatusException 처리 — 원래 HTTP 상태 코드 그대로 전달
	 *
	 * <p>NoResourceFoundException(404), MethodNotAllowedException(405) 등 Spring 내부 예외가 {@code
	 * GlobalExceptionHandler.handleGeneric}에 잡혀 500으로 변환되는 것을 방지한다.
	 *
	 * @param ex ResponseStatusException
	 * @return 원래 HTTP 상태 코드
	 */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Void> handleResponseStatus(ResponseStatusException ex) {
		return ResponseEntity.status(ex.getStatusCode()).build();
	}

	/**
	 * 예상치 못한 예외 처리 (스택트레이스 외부 노출 금지)
	 *
	 * <p>Spring 6.1+ {@link ErrorResponse} 구현체(NoResourceFoundException 등)는 원래 HTTP 상태 코드를 그대로 반환하여
	 * 500으로 변환되는 것을 방지한다.
	 *
	 * @param ex 예외
	 * @return 500 INTERNAL_SERVER_ERROR 또는 ErrorResponse의 원래 상태 코드
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<?> handleGeneric(Exception ex) {
		if (ex instanceof ErrorResponse errorResponse) {
			return ResponseEntity.status(errorResponse.getStatusCode()).build();
		}
		log.error("Unexpected error occurred", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ApiError("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
	}
}
