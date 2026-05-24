package com.econo.auth.api.exception;

import com.econo.auth.core.member.exception.InvalidCredentialsException;
import com.econo.auth.core.member.exception.InvalidPasswordPolicyException;
import com.econo.auth.core.member.exception.MemberAlreadyExistsException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
	 * 인증 실패 예외 처리
	 *
	 * @param ex 예외
	 * @return 401 INVALID_CREDENTIALS
	 */
	@ExceptionHandler(InvalidCredentialsException.class)
	public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(new ApiError("INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다."));
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
	 * 예상치 못한 예외 처리 (스택트레이스 외부 노출 금지)
	 *
	 * @param ex 예외
	 * @return 500 INTERNAL_SERVER_ERROR
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleGeneric(Exception ex) {
		log.error("Unexpected error occurred", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ApiError("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
	}
}
