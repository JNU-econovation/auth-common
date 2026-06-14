package com.econo.auth.gateway.config;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.FilterDefinition;
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
	 * <p>전 구간 논블로킹으로 동작한다. {@link
	 * com.econo.auth.gateway.presentation.controller.RouteRefreshHandler}(리액티브)와 {@link
	 * DynamicRouteInitializer}(기동)에서 구독한다.
	 *
	 * @return 재로드 완료 Mono
	 */
	public Mono<Void> reload() {
		return authApiRouteClient
				.fetchEnabledRoutes()
				.doOnSubscribe(s -> log.info("DynamicRouteDefinitionRepository: 라우트 재로드 시작"))
				.doOnNext(this::replaceCache)
				.then();
	}

	/** 새로 받은 라우트 목록으로 캐시를 전량 교체한다. */
	private void replaceCache(List<AuthApiRouteClient.RouteDto> routes) {
		Map<String, RouteDefinition> newCache = new HashMap<>();
		for (AuthApiRouteClient.RouteDto dto : routes) {
			if (dto.enabled() && dto.pathPrefix() != null && !dto.pathPrefix().isBlank()) {
				newCache.put(dto.routeId(), toRouteDefinition(dto));
			}
		}
		cache.clear();
		cache.putAll(newCache);
		log.info("DynamicRouteDefinitionRepository: 라우트 재로드 완료. count={}", cache.size());
	}

	/**
	 * RouteDto → RouteDefinition 변환
	 *
	 * <p><b>치환(rewrite) 모델</b>: {@code pathPrefix}를 {@code upstreamUrl}로 치환한다. 즉 {@code pathPrefix}
	 * 뒤의 나머지 경로를 {@code upstreamUrl}(경로 포함)에 그대로 이어붙여 전달한다. 라우트 URI는 호스트부(scheme://authority)만 사용하고,
	 * 경로 결합은 {@code RewritePath} 필터로 처리한다(Spring Cloud Gateway는 라우트 URI의 경로를 무시하기 때문).
	 *
	 * <p>예: pathPrefix {@code /api/eeos}, upstreamUrl {@code https://host/api}
	 *
	 * <ul>
	 *   <li>{@code /api/eeos/programs} → {@code https://host/api/programs}
	 *   <li>{@code /api/eeos} → {@code https://host/api}
	 * </ul>
	 *
	 * <p>upstreamUrl에 경로가 없으면(예: {@code https://host}) prefix만 제거되어 루트 기준으로 전달된다. 정적 보호 라우트({@link
	 * GatewayRoutingConfig})는 auth-api가 전체 경로를 기대하므로 이 치환을 쓰지 않는다(의도적 차이).
	 *
	 * @param dto 라우트 DTO
	 * @return RouteDefinition 인스턴스
	 */
	private RouteDefinition toRouteDefinition(AuthApiRouteClient.RouteDto dto) {
		RouteDefinition definition = new RouteDefinition();
		definition.setId(dto.routeId());

		URI upstream = URI.create(dto.upstreamUrl());
		// 라우트 URI는 scheme://authority(host:port)만 사용 — 경로는 RewritePath로 처리
		definition.setUri(URI.create(upstream.getScheme() + "://" + upstream.getAuthority()));

		// Path predicate: pathPrefix/** 패턴으로 매칭
		PredicateDefinition predicate = new PredicateDefinition();
		predicate.setName("Path");
		predicate.setArgs(Map.of("_genkey_0", dto.pathPrefix() + "/**"));
		definition.setPredicates(List.of(predicate));

		// RewritePath: pathPrefix를 upstreamUrl 경로(base path)로 치환, 나머지 경로는 그대로 이어붙임
		String basePath = normalizeBasePath(upstream.getRawPath());
		String regexp = "^" + Pattern.quote(dto.pathPrefix()) + "(?<remaining>.*)$";
		FilterDefinition rewrite = new FilterDefinition();
		rewrite.setName("RewritePath");
		rewrite.setArgs(Map.of("regexp", regexp, "replacement", basePath + "${remaining}"));
		definition.setFilters(List.of(rewrite));

		return definition;
	}

	/** upstreamUrl의 경로부를 base path로 정규화한다(빈 경로·루트는 "", 후행 슬래시 제거). */
	private String normalizeBasePath(String rawPath) {
		if (rawPath == null || rawPath.equals("/")) {
			return "";
		}
		return rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
	}
}
