package com.econo.auth.api.exception;

/** 클라이언트 ID로 클라이언트를 찾을 수 없을 때 */
public class InvalidClientException extends RuntimeException {

	public InvalidClientException() {
		super("존재하지 않는 클라이언트입니다.");
	}
}
