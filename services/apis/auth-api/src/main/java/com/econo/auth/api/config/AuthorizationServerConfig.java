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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
public class AuthorizationServerConfig {

	@Value("${AUTH_ISSUER_URI:http://localhost:8080}")
	private String issuerUri;

	@Value("${auth.frontend-login-url:http://localhost:3000/login}")
	private String frontendLoginUrl;

	@Value("${CORS_ALLOWED_ORIGINS:http://localhost:3000}")
	private String corsAllowedOrigins;

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
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
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

	/**
	 * SAS/앱 필터체인 공용 CORS 설정 소스 — api-design-plan.md CORS 정책 표 기준 경로별 분리
	 *
	 * <ul>
	 *   <li>{@code /api/v1/auth/**}: 프런트 오리진 명시, GET/POST/OPTIONS, allowCredentials=true
	 *   <li>{@code /oauth2/token}: 프런트 오리진 명시, POST/OPTIONS, allowCredentials=false
	 *   <li>{@code /oauth2/authorize}: 프런트 오리진 명시, GET/OPTIONS, allowCredentials=true
	 *   <li>{@code /userinfo}: 프런트 오리진 명시, GET/OPTIONS, allowCredentials=false
	 *   <li>{@code /.well-known/**}: 전체 공개(*), GET, allowCredentials=false
	 *   <li>{@code /oauth2/jwks}: 전체 공개(*), GET, allowCredentials=false
	 * </ul>
	 *
	 * @return {@link CorsConfigurationSource}
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

		// /api/v1/auth/** — 로그인·회원가입·로그아웃 (세션 쿠키 포함)
		CorsConfiguration authConfig = new CorsConfiguration();
		authConfig.addAllowedOrigin(corsAllowedOrigins);
		authConfig.addAllowedMethod("GET");
		authConfig.addAllowedMethod("POST");
		authConfig.addAllowedMethod("OPTIONS");
		authConfig.addAllowedHeader("*");
		authConfig.setAllowCredentials(true);
		source.registerCorsConfiguration("/api/v1/auth/**", authConfig);

		// /oauth2/token — 토큰 교환 (Bearer 없음, Credentials 불필요)
		CorsConfiguration tokenConfig = new CorsConfiguration();
		tokenConfig.addAllowedOrigin(corsAllowedOrigins);
		tokenConfig.addAllowedMethod("POST");
		tokenConfig.addAllowedMethod("OPTIONS");
		tokenConfig.addAllowedHeader("*");
		tokenConfig.setAllowCredentials(false);
		source.registerCorsConfiguration("/oauth2/token", tokenConfig);

		// /oauth2/authorize — navigate 또는 preflight
		CorsConfiguration authorizeConfig = new CorsConfiguration();
		authorizeConfig.addAllowedOrigin(corsAllowedOrigins);
		authorizeConfig.addAllowedMethod("GET");
		authorizeConfig.addAllowedMethod("OPTIONS");
		authorizeConfig.addAllowedHeader("*");
		authorizeConfig.setAllowCredentials(true);
		source.registerCorsConfiguration("/oauth2/authorize", authorizeConfig);

		// /userinfo — UserInfo 조회
		CorsConfiguration userInfoConfig = new CorsConfiguration();
		userInfoConfig.addAllowedOrigin(corsAllowedOrigins);
		userInfoConfig.addAllowedMethod("GET");
		userInfoConfig.addAllowedMethod("OPTIONS");
		userInfoConfig.addAllowedHeader("*");
		userInfoConfig.setAllowCredentials(false);
		source.registerCorsConfiguration("/userinfo", userInfoConfig);

		// /.well-known/** — Discovery (공개)
		CorsConfiguration wellKnownConfig = new CorsConfiguration();
		wellKnownConfig.addAllowedOriginPattern("*");
		wellKnownConfig.addAllowedMethod("GET");
		wellKnownConfig.addAllowedHeader("*");
		wellKnownConfig.setAllowCredentials(false);
		source.registerCorsConfiguration("/.well-known/**", wellKnownConfig);

		// /oauth2/jwks — 공개키 (공개)
		CorsConfiguration jwksConfig = new CorsConfiguration();
		jwksConfig.addAllowedOriginPattern("*");
		jwksConfig.addAllowedMethod("GET");
		jwksConfig.addAllowedHeader("*");
		jwksConfig.setAllowCredentials(false);
		source.registerCorsConfiguration("/oauth2/jwks", jwksConfig);

		return source;
	}
}
