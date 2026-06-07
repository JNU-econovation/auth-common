package com.econo.auth.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auth.redirect.* 설정 바인딩
 *
 * <p>WEB 로그인 clientId 미전달·미등록·redirect_uri 없음 시 리다이렉트할 안전한 기본 URL을 제공한다. auth.frontend-login-url과는
 * 역할이 다르다: frontend-login-url은 SAS /oauth2/authorize 미인증 진입 시 SPA 로그인 페이지로 리다이렉트하는 용도이며, 이 설정은 JSON
 * 로그인 API(경로 A) WEB 302 fallback 전용이다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "auth.redirect")
public class AuthRedirectProperties {

	/**
	 * WEB 로그인 clientId 미전달·미등록·redirect_uri 없음 시 리다이렉트할 안전한 기본 목적지 URL.
	 *
	 * <p>환경변수 {@code REDIRECT_DEFAULT_URL}로 외부화. 기본값: {@code http://localhost:3000}
	 */
	private String defaultUrl = "http://localhost:3000";
}
