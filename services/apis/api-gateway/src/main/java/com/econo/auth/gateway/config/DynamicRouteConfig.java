package com.econo.auth.gateway.config;

import com.econo.auth.gateway.presentation.controller.RouteRefreshHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 동적 라우팅 컴포넌트 빈 등록 설정
 *
 * <p>{@link AuthApiRouteClient}, {@link DynamicRouteDefinitionRepository}, {@link
 * RouteRefreshHandler}를 빈으로 등록한다.
 *
 * <p>기동 완료 후 초기 로드는 {@link DynamicRouteInitializer}가 담당한다(자기참조 순환 회피를 위해 분리).
 */
@Configuration
public class DynamicRouteConfig {

	@Value("${AUTH_API_URI:http://localhost:8081}")
	private String authApiUri;

	@Value("${GATEWAY_INTERNAL_SECRET:dev-secret}")
	private String internalSecret;

	/**
	 * AuthApiRouteClient 빈 등록
	 *
	 * @return AuthApiRouteClient 인스턴스
	 */
	@Bean
	public AuthApiRouteClient authApiRouteClient() {
		WebClient webClient = WebClient.builder().baseUrl(authApiUri).build();
		return new AuthApiRouteClient(webClient, internalSecret);
	}

	/**
	 * DynamicRouteDefinitionRepository 빈 등록
	 *
	 * @param authApiRouteClient AuthApiRouteClient 인스턴스
	 * @return DynamicRouteDefinitionRepository 인스턴스
	 */
	@Bean
	public DynamicRouteDefinitionRepository dynamicRouteDefinitionRepository(
			AuthApiRouteClient authApiRouteClient) {
		return new DynamicRouteDefinitionRepository(authApiRouteClient);
	}

	/**
	 * RouteRefreshHandler 빈 등록
	 *
	 * @param dynamicRouteDefinitionRepository DynamicRouteDefinitionRepository 인스턴스
	 * @param eventPublisher ApplicationEventPublisher 인스턴스
	 * @return RouteRefreshHandler 인스턴스
	 */
	@Bean
	public RouteRefreshHandler routeRefreshHandler(
			DynamicRouteDefinitionRepository dynamicRouteDefinitionRepository,
			ApplicationEventPublisher eventPublisher) {
		return new RouteRefreshHandler(
				dynamicRouteDefinitionRepository, eventPublisher, internalSecret);
	}
}
