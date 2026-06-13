package com.econo.auth.login.exception;

/**
 * 토큰 디코딩 실패 시 throw되는 도메인 예외
 *
 * <p>{@link com.econo.auth.login.application.repository.TokenDecoder#decode(String)} 실패 시 throw된다.
 * Spring 타입 의존 없음. {@code ReissueController}에서 catch하여 401 {@code REFRESH_TOKEN_INVALID}를 반환한다.
 */
public class InvalidTokenException extends RuntimeException {

	/**
	 * 메시지와 원인 예외로 생성한다
	 *
	 * @param message 오류 메시지
	 * @param cause 원인 예외
	 */
	public InvalidTokenException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * 메시지만으로 생성한다
	 *
	 * @param message 오류 메시지
	 */
	public InvalidTokenException(String message) {
		super(message);
	}
}
