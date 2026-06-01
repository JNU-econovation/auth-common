package com.econo.auth.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 지원하지 않는 그랜트 타입 예외 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UnsupportedGrantTypeException extends RuntimeException {

	public UnsupportedGrantTypeException(String grantType) {
		super("지원하지 않는 그랜트 타입입니다: " + grantType);
	}
}
