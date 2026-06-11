package com.econo.auth.client.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 클라이언트 등록 한도 초과 예외 (1인 최대 5개) */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClientLimitExceededException extends RuntimeException {

	public ClientLimitExceededException() {
		super("클라이언트 등록 한도를 초과했습니다. 회원당 최대 5개까지 등록할 수 있습니다.");
	}
}
