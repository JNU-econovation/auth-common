package com.econo.auth.api.application.usecase;

/**
 * 로그인 리다이렉트 결정 유스케이스 입력 포트 인터페이스 (엄격 DIP)
 *
 * <p>{@code JsonLoginAuthenticationFilter}(config/security), {@code
 * SecurityConfig}(config/security)가 {@code LoginRedirectResolver} 구현체를 직접 주입하는 것을 막는 seam.
 */
public interface LoginRedirectUseCase {

	/**
	 * clientId에 등록된 redirect_uri를 조회하여 최종 리다이렉트 목적지 URL을 결정한다
	 *
	 * @param clientId OAuth 클라이언트 ID (null·blank 허용)
	 * @param defaultUrl clientId 미전달·미등록·redirect_uri 없음 시 사용할 안전한 기본 URL
	 * @return 결정된 리다이렉트 목적지 URL
	 */
	String resolve(String clientId, String defaultUrl);
}
