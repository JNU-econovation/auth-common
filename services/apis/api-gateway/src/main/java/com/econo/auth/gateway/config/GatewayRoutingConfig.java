package com.econo.auth.gateway.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Cloud Gateway 라우팅 설정
 *
 * <p>SAS OAuth 엔드포인트({@code /oauth2/**}, {@code /.well-known/**}, {@code /userinfo})는 Gateway를 통해
 * auth-api로 라우팅된다. issuer는 {@code AUTH_ISSUER_URI}(Gateway 공개 URL) 기준이므로 토큰 내 엔드포인트 URL이 Gateway
 * 도메인을 가리킨다. Gateway 내부에서 JWKS fetch 시에는 {@code AUTH_JWKS_URI}(auth-api 내부 주소)를 별도 사용하여 자기참조 루프를
 * 방지한다.
 */
@Configuration
@RequiredArgsConstructor
public class GatewayRoutingConfig {

	@Value("${AUTH_API_URI:http://localhost:8081}")
	private String authApiUri;

	/**
	 * 라우트 설정 — auth-api 경로 및 SAS OAuth 엔드포인트 포함
	 *
	 * @param builder RouteLocatorBuilder
	 * @return RouteLocator
	 */
	@Bean
	public RouteLocator routes(RouteLocatorBuilder builder) {
		return builder
				.routes()
				.route("auth-api", r -> r.path("/api/v1/auth/**").uri(authApiUri))
				.route("sas-oauth2", r -> r.path("/oauth2/**").uri(authApiUri))
				.route("sas-well-known", r -> r.path("/.well-known/**").uri(authApiUri))
				.route("sas-userinfo", r -> r.path("/userinfo").uri(authApiUri))
				.build();
	}

	/**
	 * 인증 불필요 경로 목록 — Bearer 토큰 검증을 건너뜀
	 *
	 * <p>기존 인증 경로({@code /api/v1/auth/signup}, {@code /api/v1/auth/login}, {@code
	 * /api/v1/auth/logout}) 외에 SAS 표준 엔드포인트를 추가한다. {@code /api/v1/auth/login}은 세션 수립 엔드포인트로 여전히
	 * permit이나, 기존 JWT 쿠키 발급과 달리 서버 세션을 수립하는 목적임을 명시.
	 *
	 * @return permit 경로 접두사 리스트 ({@link BearerToPassportFilter#isProtectedPath}에서 startsWith 매칭)
	 */
	public List<String> permittedPaths() {
		return List.of(
				"/api/v1/auth/signup",
				"/api/v1/auth/login",
				"/api/v1/auth/logout",
				"/oauth2/",
				"/.well-known/",
				"/userinfo");
	}
}
