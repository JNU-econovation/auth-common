package com.econo.auth.gateway.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring Cloud Gateway 라우팅 설정 */
@Configuration
@RequiredArgsConstructor
public class GatewayRoutingConfig {

	@Value("${AUTH_API_URI:http://localhost:8081}")
	private String authApiUri;

	/**
	 * 라우트 설정
	 *
	 * @param builder RouteLocatorBuilder
	 * @return RouteLocator
	 */
	@Bean
	public RouteLocator routes(RouteLocatorBuilder builder) {
		return builder
				.routes()
				.route("auth-api", r -> r.path("/api/v1/auth/**").uri(authApiUri))
				.build();
	}

	/**
	 * 인증 불필요 경로 목록
	 *
	 * @return permit 경로 리스트
	 */
	public List<String> permittedPaths() {
		return List.of("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/logout");
	}
}
