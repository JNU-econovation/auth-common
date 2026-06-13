package com.econo.auth.gateway.config;

import com.econo.auth.gateway.presentation.controller.RouteRefreshHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 동적 라우팅 컴포넌트 빈 등록 설정
 *
 * <p>{@link AuthApiRouteClient}, {@link DynamicRouteDefinitionRepository}, {@link
 * RouteRefreshHandler}를 빈으로 등록한다.
 *
 * <p>기동 완료 후 {@link ApplicationReadyEvent}에서 동적 라우트를 초기 로드한다. auth-api 미기동 시에도 예외를 흡수하여 게이트웨이 기동을
 * 막지 않으며, 이후 refresh 엔드포인트로 채울 수 있다.
 */
@Slf4j
@Configuration
public class DynamicRouteConfig {

	@Value("${AUTH_API_URI:http://localhost:8081}")
	private String authApiUri;

	@Value("${GATEWAY_INTERNAL_SECRET:dev-secret}")
	private String internalSecret;

	@Autowired private DynamicRouteDefinitionRepository dynamicRouteDefinitionRepository;

	@Autowired private ApplicationEventPublisher eventPublisher;

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

	/**
	 * 게이트웨이 기동 완료 후 동적 라우트 초기 로드
	 *
	 * <p>auth-api가 아직 기동되지 않은 경우 {@link AuthApiRouteClient#fetchEnabledRoutes()}가 예외를 흡수하고 빈 목록을
	 * 반환하므로 게이트웨이 기동이 막히지 않는다. 이후 라우트 CRUD를 통한 refresh로 캐시가 채워진다.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		log.info("DynamicRouteConfig: 기동 완료 후 동적 라우트 초기 로드 시작");
		try {
			dynamicRouteDefinitionRepository.reload();
			eventPublisher.publishEvent(new RefreshRoutesEvent(this));
			log.info("DynamicRouteConfig: 동적 라우트 초기 로드 완료");
		} catch (Exception e) {
			log.warn(
					"DynamicRouteConfig: 동적 라우트 초기 로드 실패 — 빈 캐시로 계속 기동. 이후 refresh로 채울 수 있음: {}",
					e.getMessage());
		}
	}
}
