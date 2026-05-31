package com.econo.auth.member.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 인증 실패 예외 (HTTP 401) */
@Getter
public class InvalidCredentialsException extends RuntimeException {

	private static final String FIXED_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다";

	private final HttpStatus httpStatus = HttpStatus.UNAUTHORIZED;
	private final String errorCode = "INVALID_CREDENTIALS";

	private InvalidCredentialsException() {
		super(FIXED_MESSAGE);
	}

	/**
	 * 인증 실패 예외 생성 (사용자 열거 방지를 위해 고정 메시지 사용)
	 *
	 * @return InvalidCredentialsException 인스턴스
	 */
	public static InvalidCredentialsException of() {
		return new InvalidCredentialsException();
	}
}
