package com.econo.auth.api.exception;

import com.econo.auth.client.exception.ClientLimitExceededException;
import com.econo.auth.client.exception.DuplicateClientNameException;
import com.econo.auth.client.exception.InvalidClientException;
import com.econo.auth.client.exception.RedirectUriRequiredException;
import com.econo.auth.client.exception.RouteNotFoundException;
import com.econo.auth.client.exception.RoutePathConflictException;
import com.econo.auth.client.exception.RouteProtectedException;
import com.econo.auth.client.exception.RouteUpstreamInvalidException;
import com.econo.auth.client.exception.UnsupportedGrantTypeException;
import com.econo.auth.member.exception.InvalidPasswordPolicyException;
import com.econo.auth.member.exception.MemberAlreadyExistsException;
import com.econo.auth.member.exception.MemberNotFoundException;
import com.econo.common.auth.core.passport.PassportException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
	 * Passport 인증/인가 예외 처리
	 *
	 * <ul>
	 *   <li>401(UNAUTHORIZED) → AUTH_UNAUTHORIZED
	 *   <li>403(FORBIDDEN) → FORBIDDEN
	 *   <li>그 외 → e.getHttpStatus() + e.getErrorCode()
	 * </ul>
	 *
	 * @param e PassportException
	 * @return 상태 코드에 맞는 ApiError
	 */
	@ExceptionHandler(PassportException.class)
	public ResponseEntity<ApiError> handlePassportException(PassportException e) {
		String errorCode;
		if (e.getHttpStatus() == HttpStatus.UNAUTHORIZED) {
			errorCode = "AUTH_UNAUTHORIZED";
		} else if (e.getHttpStatus() == HttpStatus.FORBIDDEN) {
			errorCode = "FORBIDDEN";
		} else {
			errorCode = e.getErrorCode();
		}
		return ResponseEntity.status(e.getHttpStatus()).body(new ApiError(errorCode, e.getMessage()));
	}

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
	 * 회원 미존재 예외 처리 — MemberNotFoundException → 404 MEMBER_NOT_FOUND
	 *
	 * @param ex 예외
	 * @return 404 MEMBER_NOT_FOUND
	 */
	@ExceptionHandler(MemberNotFoundException.class)
	public ResponseEntity<ApiError> handleMemberNotFound(MemberNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ApiError("MEMBER_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(MemberAlreadyExistsException.class)
	public ResponseEntity<ApiError> handleMemberAlreadyExists(MemberAlreadyExistsException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ApiError("MEMBER_ALREADY_EXISTS", "이미 사용 중인 아이디입니다."));
	}

	/**
	 * 클라이언트 미존재 예외 처리 — ClientRedirectUriService.findByClientId 등에서 발생
	 *
	 * @param ex 예외
	 * @return 404 CLIENT_NOT_FOUND
	 */
	@ExceptionHandler(InvalidClientException.class)
	public ResponseEntity<ApiError> handleInvalidClient(InvalidClientException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ApiError("CLIENT_NOT_FOUND", ex.getMessage()));
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
	 * @return 400 INVALID_ARGUMENT
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiError("INVALID_ARGUMENT", ex.getMessage(), LocalDateTime.now(), null));
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
	 * redirectUris 누락 예외 처리
	 *
	 * @param ex 예외
	 * @return 400 REDIRECT_URI_REQUIRED
	 */
	@ExceptionHandler(RedirectUriRequiredException.class)
	public ResponseEntity<ApiError> handleRedirectUriRequired(RedirectUriRequiredException ex) {
		return ResponseEntity.badRequest().body(new ApiError("REDIRECT_URI_REQUIRED", ex.getMessage()));
	}

	/**
	 * 지원하지 않는 그랜트 타입 예외 처리
	 *
	 * @param ex 예외
	 * @return 400 UNSUPPORTED_GRANT_TYPE
	 */
	@ExceptionHandler(UnsupportedGrantTypeException.class)
	public ResponseEntity<ApiError> handleUnsupportedGrantType(UnsupportedGrantTypeException ex) {
		return ResponseEntity.badRequest()
				.body(new ApiError("UNSUPPORTED_GRANT_TYPE", ex.getMessage()));
	}

	/**
	 * 중복 클라이언트 이름 예외 처리
	 *
	 * @param ex 예외
	 * @return 409 DUPLICATE_CLIENT_NAME
	 */
	@ExceptionHandler(DuplicateClientNameException.class)
	public ResponseEntity<ApiError> handleDuplicateClientName(DuplicateClientNameException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ApiError("DUPLICATE_CLIENT_NAME", ex.getMessage()));
	}

	/**
	 * 클라이언트 등록 한도 초과 예외 처리
	 *
	 * @param ex 예외
	 * @return 422 CLIENT_LIMIT_EXCEEDED
	 */
	@ExceptionHandler(ClientLimitExceededException.class)
	public ResponseEntity<ApiError> handleClientLimitExceeded(ClientLimitExceededException ex) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
				.body(new ApiError("CLIENT_LIMIT_EXCEEDED", ex.getMessage()));
	}

	/**
	 * 라우트 미존재 예외 처리 — 404 ROUTE_NOT_FOUND
	 *
	 * @param ex 예외
	 * @return 404 ROUTE_NOT_FOUND
	 */
	@ExceptionHandler(RouteNotFoundException.class)
	public ResponseEntity<ApiError> handleRouteNotFound(RouteNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ApiError("ROUTE_NOT_FOUND", ex.getMessage()));
	}

	/**
	 * 라우트 경로 충돌 예외 처리 — 409 ROUTE_PATH_CONFLICT
	 *
	 * @param ex 예외
	 * @return 409 ROUTE_PATH_CONFLICT
	 */
	@ExceptionHandler(RoutePathConflictException.class)
	public ResponseEntity<ApiError> handleRoutePathConflict(RoutePathConflictException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ApiError("ROUTE_PATH_CONFLICT", ex.getMessage()));
	}

	/**
	 * 라우트 업스트림 URL 검증 실패 예외 처리 — 400 ROUTE_UPSTREAM_INVALID
	 *
	 * @param ex 예외
	 * @return 400 ROUTE_UPSTREAM_INVALID
	 */
	@ExceptionHandler(RouteUpstreamInvalidException.class)
	public ResponseEntity<ApiError> handleRouteUpstreamInvalid(RouteUpstreamInvalidException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ApiError("ROUTE_UPSTREAM_INVALID", ex.getMessage()));
	}

	/**
	 * 보호 경로 가로채기/삭제 시도 예외 처리 — 403 ROUTE_PROTECTED
	 *
	 * @param ex 예외
	 * @return 403 ROUTE_PROTECTED
	 */
	@ExceptionHandler(RouteProtectedException.class)
	public ResponseEntity<ApiError> handleRouteProtected(RouteProtectedException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(new ApiError("ROUTE_PROTECTED", ex.getMessage()));
	}

	/**
	 * DB UNIQUE 제약 위반 — pathPrefix/clientName 중복 등록 시
	 *
	 * @param ex 예외
	 * @return 409 DUPLICATE_RESOURCE
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
		log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ApiError("DUPLICATE_RESOURCE", "이미 등록된 리소스입니다. (경로 접두사 또는 클라이언트 이름 중복)"));
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
