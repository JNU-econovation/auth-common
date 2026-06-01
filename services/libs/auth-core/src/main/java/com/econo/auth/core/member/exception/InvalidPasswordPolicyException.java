package com.econo.auth.core.member.exception;

/** 비밀번호 정책 위반 예외 — HTTP 매핑은 GlobalExceptionHandler가 담당 */
public class InvalidPasswordPolicyException extends RuntimeException {

	public static final String ERROR_CODE = "INVALID_PASSWORD_POLICY";

	private InvalidPasswordPolicyException(String message) {
		super(message);
	}

	/**
	 * 비밀번호 정책 위반 예외 생성
	 *
	 * @param reason 위반 사유
	 * @return InvalidPasswordPolicyException 인스턴스
	 */
	public static InvalidPasswordPolicyException of(String reason) {
		return new InvalidPasswordPolicyException("비밀번호는 대문자·소문자·숫자·특수기호를 각 1자 이상 포함해야 합니다: " + reason);
	}
}
