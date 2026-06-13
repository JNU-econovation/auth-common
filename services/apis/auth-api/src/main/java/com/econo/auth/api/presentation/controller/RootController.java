package com.econo.auth.api.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 루트 헬스 엔드포인트 — 애플리케이션 이름과 기동 시각·uptime 반환
 *
 * <p>{@code GET /} 로 호출하면 헬스 확인용 메타 정보를 반환한다. 인증 불필요(SecurityConfig에서 permitAll).
 */
@Tag(name = "Health")
@RestController
public class RootController {

	private static final String APPLICATION_NAME = "auth-api";

	@Operation(summary = "루트 헬스체크", description = "애플리케이션 이름, 기동 시각(startedAt), uptime을 반환한다.")
	@GetMapping("/")
	public Map<String, Object> root() {
		long startTimeMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
		long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
		return Map.of(
				"application", APPLICATION_NAME,
				"startedAt", Instant.ofEpochMilli(startTimeMillis).toString(),
				"uptime", Duration.ofMillis(uptimeMillis).toString());
	}
}
