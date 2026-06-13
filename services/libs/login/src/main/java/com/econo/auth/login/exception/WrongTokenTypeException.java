package com.econo.auth.login.exception;

/**
 * token_type 클레임 불일치 시 throw되는 도메인 예외
 *
 * <p>{@code LoginTokenService.verifyRefreshTokenAndGetMemberId()} 내부에서 {@code token_type}이 {@code
 * "refresh"}가 아닐 때 throw된다. Spring 타입 의존 없음. {@code ReissueController}에서 catch하여 401 {@code
 * REFRESH_TOKEN_INVALID}를 반환한다.
 */
public class WrongTokenTypeException extends RuntimeException {

	/**
	 * 메시지로 생성한다
	 *
	 * @param message 오류 메시지
	 */
	public WrongTokenTypeException(String message) {
		super(message);
	}

	/**
	 * WrongTokenTypeException 정적 팩토리 메서드
	 *
	 * @param actualType 실제 token_type 값
	 * @return WrongTokenTypeException 인스턴스
	 */
	public static WrongTokenTypeException of(String actualType) {
		return new WrongTokenTypeException("Expected refresh token but got: " + actualType);
	}
}
