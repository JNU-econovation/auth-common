package com.econo.auth.login.application.repository;

import com.econo.auth.login.application.domain.DecodedToken;
import com.econo.auth.login.exception.InvalidTokenException;

/**
 * AT/RT 디코딩 추상화 출력 포트
 *
 * <p>lib이 {@code spring-security-oauth2} 구현체에 직접 의존하지 않도록 격리하는 DIP 경계. 구현체({@code
 * NimbusTokenManager})는 auth-api의 {@code config/security/}에 위치한다.
 */
public interface TokenDecoder {

	/**
	 * JWT 문자열을 DecodedToken으로 디코딩한다
	 *
	 * @param token JWT 문자열
	 * @return 디코딩된 토큰 (subject, claims)
	 * @throws InvalidTokenException 서명 불일치, 만료, 형식 오류 등 디코딩 실패 시
	 */
	DecodedToken decode(String token) throws InvalidTokenException;
}
