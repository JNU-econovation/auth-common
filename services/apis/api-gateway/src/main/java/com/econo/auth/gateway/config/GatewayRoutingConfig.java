package com.econo.auth.gateway.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Cloud Gateway 정적 라우팅 설정
 *
 * <p>라우트는 환경변수({@code AUTH_API_URI}, {@code EEOS_API_URI})로 외부화된다. 새 서비스 추가 시 이 파일에 라우트를 추가하고
 * 재배포한다.
 */
@Configuration
public class GatewayRoutingConfig {

	@Value("${AUTH_API_URI:http://localhost:8081}")
	private String authApiUri;

	@Value("${EEOS_API_URI:http://localhost:8080}")
	private String eeosApiUri;

	@Bean
	public RouteLocator routes(RouteLocatorBuilder builder) {
		return builder
				.routes()
				// SAS OAuth2 엔드포인트 → auth-api
				.route("auth-api", r -> r.path("/api/v1/auth/**").uri(authApiUri))
				.route("sas-oauth2", r -> r.path("/oauth2/**").uri(authApiUri))
				.route("sas-well-known", r -> r.path("/.well-known/**").uri(authApiUri))
				.route("sas-userinfo", r -> r.path("/userinfo").uri(authApiUri))
				// EEOS 서비스 → eeos-be
				// Authorization 헤더를 제거하고 X-User-Passport만 전달
				// EEOS-BE는 자체 HMAC 토큰을 모르므로 Bearer를 제거해야 PassportFilter가 동작
				.route(
						"eeos",
						r ->
								r.path("/api/**")
										.filters(f -> f.removeRequestHeader("Authorization"))
										.uri(eeosApiUri))
				.build();
	}

	/** 인증 불필요 경로 — BearerToPassportFilter에서 참조 */
	public List<String> permittedPaths() {
		return List.of(
				// SAS 인증 경로
				"/api/v1/auth/signup",
				"/api/v1/auth/login",
				"/api/v1/auth/logout",
				"/api/v1/auth/reissue", // RT 쿠키 기반 — Bearer 없음
				"/oauth2/",
				"/.well-known/",
				"/userinfo",
				// Gateway 자체
				"/actuator/",
				// EEOS-BE 공개 경로
				"/api/health-check",
				"/api/auth/", // EEOS 자체 로그인
				"/api/guest/",
				"/api/slack/events");
	}
}
