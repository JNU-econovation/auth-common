package com.econo.auth.gateway.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Spring Cloud Gateway 정적 라우팅 설정 (보호 경로 전용)
 *
 * <p>auth-api 핵심 경로, Admin/Members/Clients API, Swagger, OAuth2, Well-Known 등 보호 경로만 정적으로 유지한다. 그 외
 * 동적 서비스 라우트는 {@link DynamicRouteDefinitionRepository}가 auth-api REST 로드로 처리한다.
 *
 * <p>인증 불필요 경로({@link #permittedPaths})는 {@code application.yml}의 {@code gateway.permitted-paths}에서
 * 로드되며, {@link com.econo.auth.gateway.filter.BearerToPassportFilter}가 참조한다.
 *
 * <p>동적 라우트보다 높은 우선순위를 보장하기 위해 {@code @Order(1)}을 부여한다.
 */
@Configuration
@ConfigurationProperties(prefix = "gateway")
public class GatewayRoutingConfig {

	@Value("${AUTH_API_URI:http://localhost:8081}")
	private String authApiUri;

	/** application.yml의 gateway.permitted-paths에서 주입 */
	private List<String> permittedPaths = List.of();

	public List<String> getPermittedPaths() {
		return permittedPaths;
	}

	public void setPermittedPaths(List<String> permittedPaths) {
		this.permittedPaths = permittedPaths != null ? permittedPaths : List.of();
	}

	/**
	 * BearerToPassportFilter에서 참조하는 인증 불필요 경로 목록.
	 *
	 * <p>yml에서 로드되므로 재배포 없이 경로 추가/제거 가능.
	 */
	public List<String> permittedPaths() {
		return permittedPaths;
	}

	/**
	 * 보호 경로 정적 라우트 정의 (동적 라우트보다 높은 우선순위).
	 *
	 * <p>auth-api 핵심 경로(인증, OAuth2, Admin API 등)는 정적으로 고정하여 동적 라우트가 가로채지 못하도록 보장한다. 동적 서비스 라우트는
	 * {@link DynamicRouteDefinitionRepository}가 처리한다.
	 */
	@Bean
	@Order(1)
	public RouteLocator routes(RouteLocatorBuilder builder) {
		return builder
				.routes()
				.route("auth-api", r -> r.path("/api/v1/auth/**").uri(authApiUri))
				.route("auth-admin", r -> r.path("/api/v1/admin/**").uri(authApiUri))
				.route("auth-clients", r -> r.path("/api/v1/clients/**").uri(authApiUri))
				.route("auth-members", r -> r.path("/api/v1/members/**").uri(authApiUri))
				.route("sas-oauth2", r -> r.path("/oauth2/**").uri(authApiUri))
				.route("sas-well-known", r -> r.path("/.well-known/**").uri(authApiUri))
				.route("sas-userinfo", r -> r.path("/userinfo").uri(authApiUri))
				// auth-api Swagger / OpenAPI 문서 (게이트웨이 경유 공개 열람)
				.route(
						"auth-swagger",
						r ->
								r.path("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/v3/api-docs")
										.uri(authApiUri))
				.build();
	}
}
