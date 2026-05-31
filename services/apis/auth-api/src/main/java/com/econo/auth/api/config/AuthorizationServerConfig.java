package com.econo.auth.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * Spring Authorization Server(SAS) 인가 서버 전용 필터체인 설정 — {@code @Order(1)}
 *
 * <p>SAS 표준 엔드포인트({@code /oauth2/authorize}, {@code /oauth2/token}, {@code /oauth2/jwks}, {@code
 * /userinfo}, {@code /.well-known/openid-configuration})를 자동 활성화한다. 미인증 {@code /oauth2/authorize}
 * 요청은 외부 SPA 로그인 URL({@code auth.frontend-login-url})로 302 리다이렉트한다.
 *
 * <p>issuer URL ({@code AUTH_ISSUER_URI})은 Gateway 공개 URL이어야 한다. 이로써 토큰 내 엔드포인트 URL이 Gateway 도메인을
 * 가리키게 된다.
 */
@Slf4j
@Configuration
@lombok.RequiredArgsConstructor
public class AuthorizationServerConfig {

	private final DynamicCorsConfigurationSource dynamicCorsConfigurationSource;

	@Value("${AUTH_ISSUER_URI:http://localhost:8080}")
	private String issuerUri;

	@Value("${auth.frontend-login-url:http://localhost:3000/login}")
	private String frontendLoginUrl;

	/**
	 * SAS 인가 서버 전용 SecurityFilterChain — {@code @Order(1)}
	 *
	 * <p>OIDC 활성화. 미인증 진입 시 외부 SPA 로그인 URL로 리다이렉트.
	 *
	 * @param http HttpSecurity
	 * @return {@link SecurityFilterChain}
	 * @throws Exception 설정 오류
	 */
	@Bean
	@Order(1)
	public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http)
			throws Exception {
		OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
		http.getConfigurer(OAuth2AuthorizationServerConfigurer.class).oidc(Customizer.withDefaults());

		http.exceptionHandling(
						ex ->
								ex.defaultAuthenticationEntryPointFor(
										new LoginUrlAuthenticationEntryPoint(frontendLoginUrl),
										new org.springframework.security.web.util.matcher.MediaTypeRequestMatcher(
												MediaType.TEXT_HTML)))
				.cors(cors -> cors.configurationSource(dynamicCorsConfigurationSource))
				.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));

		return http.build();
	}

	/**
	 * AuthorizationServerSettings 빈 등록 — issuer URL 설정
	 *
	 * <p>issuer는 {@code AUTH_ISSUER_URI} 환경변수로 주입되며, Gateway 공개 URL을 사용한다. JWKS URI, Discovery
	 * document 등 모든 엔드포인트 URL이 Gateway 도메인으로 발행된다.
	 *
	 * @return {@link AuthorizationServerSettings}
	 */
	@Bean
	public AuthorizationServerSettings authorizationServerSettings() {
		return AuthorizationServerSettings.builder().issuer(issuerUri).build();
	}
}
