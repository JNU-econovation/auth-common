package com.econo.auth.gateway.config;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 동적 라우트 정의 저장소
 *
 * <p>인메모리 {@link ConcurrentHashMap} 캐시를 primary 저장소로 사용한다. 기동 시({@link #reload()}) auth-api {@code
 * GET /api/v1/internal/routes}를 {@link AuthApiRouteClient}로 호출하여 enabled=true 라우트 전량을 로드한다. {@link
 * org.springframework.cloud.gateway.event.RefreshRoutesEvent} 발행 직전에 {@link #reload()}를 호출하여 캐시를 전량
 * 교체한다.
 *
 * <p>{@link Ordered#LOWEST_PRECEDENCE}로 설정하여 {@link GatewayRoutingConfig}의 정적 보호 라우트보다 낮은 우선순위를
 * 보장한다.
 */
@Slf4j
@RequiredArgsConstructor
public class DynamicRouteDefinitionRepository implements RouteDefinitionRepository, Ordered {

	private final AuthApiRouteClient authApiRouteClient;

	private final ConcurrentHashMap<String, RouteDefinition> cache =
			new ConcurrentHashMap<>(); // Route 매핑 테이블

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	/**
	 * 캐시에서 RouteDefinition 반환
	 *
	 * @return 캐시된 RouteDefinition Flux
	 */
	@Override
	public Flux<RouteDefinition> getRouteDefinitions() {
		return Flux.fromIterable(cache.values());
	}

	/**
	 * 캐시에 RouteDefinition 추가 (Spring 내부 사용)
	 *
	 * @param route 추가할 RouteDefinition Mono
	 * @return Mono&lt;Void&gt;
	 */
	@Override
	public Mono<Void> save(Mono<RouteDefinition> route) {
		return route.doOnNext(r -> cache.put(r.getId(), r)).then();
	}

	/**
	 * 캐시에서 RouteDefinition 제거 (Spring 내부 사용)
	 *
	 * @param routeId 제거할 라우트 ID Mono
	 * @return Mono&lt;Void&gt;
	 */
	@Override
	public Mono<Void> delete(Mono<String> routeId) {
		return routeId.doOnNext(cache::remove).then();
	}

	/**
	 * auth-api로부터 라우트 전량 재로드하여 캐시 교체
	 *
	 * <p>{@link com.econo.auth.gateway.presentation.controller.RouteRefreshHandler}에서 호출된다.
	 */
	public void reload() {
		log.info("DynamicRouteDefinitionRepository: 라우트 재로드 시작");
		try {
			List<AuthApiRouteClient.RouteDto> routes = authApiRouteClient.fetchEnabledRoutes();
			ConcurrentHashMap<String, RouteDefinition> newCache = new ConcurrentHashMap<>();
			for (AuthApiRouteClient.RouteDto dto : routes) {
				if (dto.enabled() && dto.pathPrefix() != null && !dto.pathPrefix().isBlank()) {
					RouteDefinition definition = toRouteDefinition(dto);
					newCache.put(dto.routeId(), definition);
				}
			}
			cache.clear();
			cache.putAll(newCache);
			log.info("DynamicRouteDefinitionRepository: 라우트 재로드 완료. count={}", cache.size());
		} catch (Exception e) {
			log.error("DynamicRouteDefinitionRepository: 라우트 재로드 실패", e);
		}
	}

	/**
	 * RouteDto → RouteDefinition 변환
	 *
	 * <p>pathPrefix → {@code Path=pathPrefix/**} predicate. StripPrefix 필터를 적용하지 않아 정적 보호 라우트({@link
	 * GatewayRoutingConfig})와 동작을 일치시킨다. 업스트림에는 전체 경로가 그대로 전달된다.
	 *
	 * <p><b>StripPrefix 미적용 이유</b>: 정적 라우트가 StripPrefix 없이 전체 경로를 업스트림에 전달하므로 동적 라우트도 동일하게 통일한다.
	 * 클라이언트가 요청한 전체 경로(예: {@code /api/v2/myservice/users})를 업스트림이 그대로 수신하는 것이 더 단순하고 예측 가능하다.
	 *
	 * @param dto 라우트 DTO
	 * @return RouteDefinition 인스턴스
	 */
	private RouteDefinition toRouteDefinition(AuthApiRouteClient.RouteDto dto) {
		RouteDefinition definition = new RouteDefinition();
		definition.setId(dto.routeId());
		definition.setUri(URI.create(dto.upstreamUrl()));

		// Path predicate: pathPrefix/** 패턴으로 매칭
		PredicateDefinition predicate = new PredicateDefinition();
		predicate.setName("Path");
		predicate.setArgs(Map.of("_genkey_0", dto.pathPrefix() + "/**"));
		definition.setPredicates(List.of(predicate));

		// StripPrefix 필터 미적용 — 정적 라우트와 동작 일치 (업스트림에 전체 경로 전달)
		definition.setFilters(List.of());

		return definition;
	}
}
