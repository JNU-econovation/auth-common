package com.econo.auth.gateway.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * auth-api 내부 라우트 조회 클라이언트
 *
 * <p>api-gateway가 기동 시 auth-api {@code GET /api/v1/internal/routes}를 호출하여 enabled=true 라우트 전량을
 * 로드한다.
 */
@Slf4j
@RequiredArgsConstructor
public class AuthApiRouteClient {

	private final WebClient webClient;
	private final String internalSecret;

	/**
	 * auth-api에서 enabled=true 라우트 전량 조회
	 *
	 * @return RouteDto 목록
	 */
	public List<RouteDto> fetchEnabledRoutes() {
		try {
			InternalRouteListResponse response =
					webClient
							.get()
							.uri("/api/v1/internal/routes")
							.header("X-Internal-Secret", internalSecret)
							.retrieve()
							.bodyToMono(InternalRouteListResponse.class)
							.block();

			if (response == null || response.routes() == null) {
				log.warn("auth-api로부터 빈 응답 수신");
				return List.of();
			}
			return response.routes();
		} catch (Exception e) {
			log.warn("auth-api 라우트 로드 실패: {}", e.getMessage());
			return List.of();
		}
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
