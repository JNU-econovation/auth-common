package com.econo.auth.api.application.service;

import com.econo.auth.client.application.service.GatewayRefreshClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

/**
 * api-gateway refresh HTTP 클라이언트 구현체
 *
 * <p>auth-api는 Spring MVC(Servlet) 스택이므로 {@code RestClient}(Spring 6.1+)를 사용하여 Reactive WebClient
 * 혼용을 회피한다.
 *
 * <p>refresh 실패 시 예외를 전파하지 않고 경고 로그만 남긴다 (최종 일관성 수용).
 */
@Slf4j
@RequiredArgsConstructor
public class GatewayRefreshClientImpl implements GatewayRefreshClient {

	private final RestClient restClient;
	private final String internalSecret;

	/**
	 * api-gateway에 RefreshRoutesEvent 트리거 요청
	 *
	 * <p>실패 시 예외를 흡수하고 경고 로그만 남긴다.
	 */
	@Override
	public void triggerRefresh() {
		try {
			restClient
					.post()
					.uri("/api/v1/internal/routes/refresh") //
					.header("X-Internal-Secret", internalSecret)
					.retrieve()
					.toBodilessEntity();
			log.debug("Gateway refresh triggered successfully");
		} catch (Exception e) {
			log.warn("Gateway refresh 요청 실패 (최종 일관성 수용): {}", e.getMessage());
		}
	}
}
