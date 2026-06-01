package com.econo.auth.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 중복 클라이언트 이름 예외 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateClientNameException extends RuntimeException {

	public DuplicateClientNameException(String clientName) {
		super("이미 등록된 클라이언트 이름입니다: " + clientName);
	}
}
