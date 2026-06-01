package com.econo.auth.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** authorization_code 그랜트 타입에서 redirectUris가 없을 때 발생하는 예외 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class RedirectUriRequiredException extends RuntimeException {

	public RedirectUriRequiredException() {
		super("authorization_code 그랜트 타입은 redirectUris가 필수입니다.");
	}
}
