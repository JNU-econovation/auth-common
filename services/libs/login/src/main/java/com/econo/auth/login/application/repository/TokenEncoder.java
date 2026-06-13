package com.econo.auth.login.application.repository;

import com.econo.auth.login.application.domain.TokenModel;

/**
 * AT/RT 인코딩 추상화 출력 포트
 *
 * <p>lib이 {@code spring-security-oauth2} 구현체에 직접 의존하지 않도록 격리하는 DIP 경계. 구현체({@code
 * NimbusTokenManager})는 auth-api의 {@code config/security/}에 위치한다.
 */
public interface TokenEncoder {

	/**
	 * TokenModel을 JWT 문자열로 인코딩한다
	 *
	 * @param model AT 또는 RT 인코딩 명세
	 * @return 서명된 JWT 문자열
	 */
	String encode(TokenModel model);
}
