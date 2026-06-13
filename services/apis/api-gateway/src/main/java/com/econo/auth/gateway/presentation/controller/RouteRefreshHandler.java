package com.econo.auth.gateway.presentation.controller;

import com.econo.auth.gateway.config.DynamicRouteDefinitionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이 내부 refresh 핸들러
 *
 * <p>{@code POST /api/v1/internal/routes/refresh} 요청을 처리한다. {@code X-Internal-Secret} 헤더를 검증하고 통과 시
 * {@link DynamicRouteDefinitionRepository#reload()}와 {@link RefreshRoutesEvent} 발행을 수행한다.
 */
@Slf4j
@RequiredArgsConstructor
public class RouteRefreshHandler {

	private final DynamicRouteDefinitionRepository dynamicRouteDefinitionRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final String internalSecret;

	/**
	 * refresh 요청 처리
	 *
	 * <p>{@code X-Internal-Secret} 헤더를 {@link MessageDigest#isEqual}로 상수시간 비교하여 타이밍 공격을 방지한다.
	 *
	 * <p>{@link DynamicRouteDefinitionRepository#reload()}는 논블로킹 {@code Mono}를 반환하므로 리액티브 체인으로 그대로
	 * 잇는다. 재로드가 완료된 뒤 {@link RefreshRoutesEvent}를 발행하여 게이트웨이가 갱신된 캐시로 라우트를 재구성하게 한다.
	 *
	 * @param request 요청
	 * @return 200 OK {"refreshed": true} 또는 403 FORBIDDEN
	 */
	public Mono<ServerResponse> handle(ServerRequest request) {
		String secret = request.headers().firstHeader("X-Internal-Secret");

		if (!constantTimeEquals(secret, internalSecret)) {
			log.warn("RouteRefreshHandler: X-Internal-Secret 불일치, 요청 거부");
			return ServerResponse.status(HttpStatus.FORBIDDEN).build();
		}

		return dynamicRouteDefinitionRepository
				.reload()
				.doOnSuccess(
						v -> {
							eventPublisher.publishEvent(new RefreshRoutesEvent(this));
							log.info("RouteRefreshHandler: 라우트 refresh 완료");
						})
				.then(Mono.defer(() -> ServerResponse.ok().bodyValue(Map.of("refreshed", true))));
	}

	/**
	 * 상수시간 문자열 비교 (타이밍 공격 방지)
	 *
	 * <p>null 또는 길이가 다른 경우도 안전하게 처리한다. 시크릿 값은 로그에 남기지 않는다.
	 *
	 * @param a 비교할 문자열 A (nullable)
	 * @param b 비교할 문자열 B (nullable)
	 * @return 두 문자열이 동일하면 true
	 */
	private boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		return MessageDigest.isEqual(
				a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
