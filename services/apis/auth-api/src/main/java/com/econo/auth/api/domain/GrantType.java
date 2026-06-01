package com.econo.auth.api.domain;

import com.econo.auth.api.exception.UnsupportedGrantTypeException;

/** OAuth 2.0 그랜트 타입 */
public enum GrantType {
	AUTHORIZATION_CODE,
	CLIENT_CREDENTIALS;

	/**
	 * 문자열 → GrantType 변환. 파싱 책임을 도메인으로 집중시켜 컨트롤러 중복 제거.
	 *
	 * @param value 그랜트 타입 문자열
	 * @return GrantType
	 * @throws UnsupportedGrantTypeException 지원하지 않는 값인 경우
	 */
	public static GrantType fromString(String value) {
		if (value == null) {
			throw new UnsupportedGrantTypeException("null");
		}
		return switch (value.toLowerCase()) {
			case "authorization_code" -> AUTHORIZATION_CODE;
			case "client_credentials" -> CLIENT_CREDENTIALS;
			default -> throw new UnsupportedGrantTypeException(value);
		};
	}
}
