package com.econo.auth.gateway.web;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 루트 헬스 엔드포인트 — 애플리케이션 이름과 기동 시각·uptime 반환
 *
 * <p>{@code GET /} 는 라우트 predicate에 걸리지 않아 게이트웨이가 프록시하지 않고 이 컨트롤러가 직접 처리한다.
 * BearerToPassportFilter(GlobalFilter)는 라우팅된 요청에만 적용되므로 인증 없이 응답한다.
 */
@RestController
public class RootController {

	private static final String APPLICATION_NAME = "api-gateway";

	@GetMapping("/")
	public Mono<Map<String, Object>> root() {
		long startTimeMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
		long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
		return Mono.just(
				Map.of(
						"application", APPLICATION_NAME,
						"startedAt", Instant.ofEpochMilli(startTimeMillis).toString(),
						"uptime", Duration.ofMillis(uptimeMillis).toString()));
	}
}
