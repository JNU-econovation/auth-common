package com.econo.common.auth.core.passport;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Passport 관련 예외 HTTP 상태 코드와 함께 예외 정보를 전달 */
@Getter
public class PassportException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/** HTTP 상태 코드 */
	private final HttpStatus httpStatus;
	/** 에러 코드 */
	private final String errorCode;

	/**
	 * PassportException 생성자
	 *
	 * @param httpStatus HTTP 상태 코드
	 * @param message 에러 메시지
	 */
	public PassportException(HttpStatus httpStatus, String message) {
		super(message);
		this.httpStatus = httpStatus;
		this.errorCode = generateErrorCode(httpStatus);
	}

	/**
	 * PassportException 생성자 (원인 포함)
	 *
	 * @param httpStatus HTTP 상태 코드
	 * @param message 에러 메시지
	 * @param cause 원인 예외
	 */
	public PassportException(HttpStatus httpStatus, String message, Throwable cause) {
		super(message, cause);
		this.httpStatus = httpStatus;
		this.errorCode = generateErrorCode(httpStatus);
	}

	/**
	 * PassportException 생성자 (에러 코드 지정)
	 *
	 * @param httpStatus HTTP 상태 코드
	 * @param errorCode 에러 코드
	 * @param message 에러 메시지
	 */
	public PassportException(HttpStatus httpStatus, String errorCode, String message) {
		super(message);
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
	}

	/**
	 * PassportException 생성자 (에러 코드 및 원인 지정)
	 *
	 * @param httpStatus HTTP 상태 코드
	 * @param errorCode 에러 코드
	 * @param message 에러 메시지
	 * @param cause 원인 예외
	 */
	public PassportException(
			HttpStatus httpStatus, String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
	}

	private String generateErrorCode(HttpStatus httpStatus) {
		switch (httpStatus) {
			case UNAUTHORIZED:
				return "AUTH_UNAUTHORIZED";
			case FORBIDDEN:
				return "AUTH_FORBIDDEN";
			case BAD_REQUEST:
				return "AUTH_BAD_REQUEST";
			default:
				return "AUTH_ERROR";
		}
	}

	/**
	 * 인증 실패 예외
	 *
	 * @param message 에러 메시지
	 * @return UNAUTHORIZED 상태의 PassportException
	 */
	public static PassportException unauthorized(String message) {
		return new PassportException(HttpStatus.UNAUTHORIZED, message);
	}

	/**
	 * 권한 부족 예외
	 *
	 * @param message 에러 메시지
	 * @return FORBIDDEN 상태의 PassportException
	 */
	public static PassportException forbidden(String message) {
		return new PassportException(HttpStatus.FORBIDDEN, message);
	}

	/**
	 * 잘못된 요청 예외
	 *
	 * @param message 에러 메시지
	 * @return BAD_REQUEST 상태의 PassportException
	 */
	public static PassportException badRequest(String message) {
		return new PassportException(HttpStatus.BAD_REQUEST, message);
	}

	/**
	 * 만료된 Passport 예외
	 *
	 * @param memberId 만료된 회원 ID
	 * @return UNAUTHORIZED 상태의 PassportException
	 */
	public static PassportException expired(Long memberId) {
		return new PassportException(
				HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_EXPIRED", "Expired passport : " + memberId);
	}

	/**
	 * 유효하지 않은 Passport 예외
	 *
	 * @param reason 유효하지 않은 이유
	 * @return UNAUTHORIZED 상태의 PassportException
	 */
	public static PassportException invalid(String reason) {
		return new PassportException(
				HttpStatus.UNAUTHORIZED, "AUTH_PASSPORT_INVALID", "Invalid passport: " + reason);
	}
}
