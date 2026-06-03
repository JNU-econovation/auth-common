package com.econo.auth.api.domain;

import com.econo.auth.api.exception.UnsupportedGrantTypeException;
import org.springframework.lang.Nullable;

/** OAuth 2.0 그랜트 타입 */
public enum GrantType {
	AUTHORIZATION_CODE,
	CLIENT_CREDENTIALS;

	/**
	 * 문자열 → GrantType 변환. 파싱 책임을 도메인으로 집중시켜 컨트롤러 중복 제거.
	 *
	 * <p>null 입력 시 null을 반환한다. 서비스 계층에서 {@code CLIENT_CREDENTIALS} 디폴트를 적용한다.
	 *
	 * @param value 그랜트 타입 문자열. null 허용.
	 * @return null 입력 시 null 반환. 알 수 없는 비-null 값이면 UnsupportedGrantTypeException.
	 * @throws UnsupportedGrantTypeException null이 아닌 알 수 없는 값인 경우
	 */
	@Nullable
	public static GrantType fromString(@Nullable String value) {
		if (value == null) {
			return null;
		}
		return switch (value.toLowerCase()) {
			case "authorization_code" -> AUTHORIZATION_CODE;
			case "client_credentials" -> CLIENT_CREDENTIALS;
			default -> throw new UnsupportedGrantTypeException(value);
		};
	}
}
