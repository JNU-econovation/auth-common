package com.econo.auth.gateway.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * auth-api 내부 라우트 조회 클라이언트
 *
 * <p>api-gateway가 기동 시·refresh 시 auth-api {@code GET /api/v1/internal/routes}를 호출하여 enabled=true
 * 라우트 전량을 로드한다. 전 구간 논블로킹(리액티브)으로 동작하므로 이벤트 루프 스레드에서 호출해도 안전하다.
 */
@Slf4j
@RequiredArgsConstructor
public class AuthApiRouteClient {

	private final WebClient webClient;
	private final String internalSecret;

	/**
	 * auth-api에서 enabled=true 라우트 전량 조회
	 *
	 * <p>실패·빈 응답 시 빈 목록으로 대체한다(예외를 전파하지 않음).
	 *
	 * @return RouteDto 목록 Mono
	 */
	public Mono<List<RouteDto>> fetchEnabledRoutes() {
		return webClient
				.get()
				.uri("/api/v1/internal/routes")
				.header("X-Internal-Secret", internalSecret)
				.retrieve()
				.bodyToMono(InternalRouteListResponse.class)
				.map(response -> response.routes() != null ? response.routes() : List.<RouteDto>of())
				.defaultIfEmpty(List.of())
				.onErrorResume(
						e -> {
							log.warn("auth-api 라우트 로드 실패: {}", e.getMessage());
							return Mono.just(List.of());
						});
	}

	/**
	 * auth-api 내부 라우트 목록 응답
	 *
	 * @param routes 활성화된 라우트 DTO 목록
	 */
	public record InternalRouteListResponse(List<RouteDto> routes) {}

	/**
	 * 라우트 DTO
	 *
	 * @param routeId 라우트 UUID 문자열
	 * @param pathPrefix 경로 접두사
	 * @param upstreamUrl 업스트림 서비스 URL
	 * @param enabled 활성화 여부
	 */
	public record RouteDto(String routeId, String pathPrefix, String upstreamUrl, boolean enabled) {}
}
