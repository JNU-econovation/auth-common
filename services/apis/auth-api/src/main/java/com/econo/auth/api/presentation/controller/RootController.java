package com.econo.auth.api.presentation.controller;

import com.econo.auth.api.presentation.docs.RootApiDocs;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 루트 헬스 엔드포인트 — 애플리케이션 이름과 기동 시각·uptime 반환
 *
 * <p>{@code GET /} 로 호출하면 헬스 확인용 메타 정보를 반환한다. 인증 불필요(SecurityConfig에서 permitAll).
 */
@RestController
public class RootController implements RootApiDocs {

	private static final String APPLICATION_NAME = "auth-api";

	@Override
	@GetMapping("/")
	public HealthResponse root() {
		long startTimeMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
		long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
		return new HealthResponse(
				APPLICATION_NAME,
				Instant.ofEpochMilli(startTimeMillis).toString(),
				formatUptime(uptimeMillis));
	}

	/** 가동 시간을 사람이 읽기 쉬운 "N일 N시간 N분 N초" 형태로 변환한다. */
	private static String formatUptime(long uptimeMillis) {
		Duration d = Duration.ofMillis(uptimeMillis);
		return String.format(
				"%d일 %d시간 %d분 %d초", d.toDays(), d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart());
	}

	/**
	 * 헬스 응답 DTO
	 *
	 * @param application 애플리케이션 이름
	 * @param startedAt 기동 시각 (ISO-8601)
	 * @param uptime 가동 시간 (예: "0일 1시간 17분 47초")
	 */
	public record HealthResponse(String application, String startedAt, String uptime) {}
}
