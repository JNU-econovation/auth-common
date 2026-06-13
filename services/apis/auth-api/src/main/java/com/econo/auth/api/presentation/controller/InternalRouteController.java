package com.econo.auth.api.presentation.controller;

import com.econo.auth.client.application.usecase.ManageRouteUseCase;
import com.econo.auth.client.application.usecase.ManageRouteUseCase.RouteResult;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 전용 라우트 조회 컨트롤러
 *
 * <p>api-gateway가 기동 시 auth-api를 직접 호출하여 enabled=true 라우트를 전량 로드한다. {@code X-Internal-Secret} 헤더
 * 검증으로 보호한다. Passport 인증 불필요.
 *
 * <p>게이트웨이 전용 내부 엔드포인트이므로 {@link Hidden}으로 OpenAPI 문서에서 제외한다(CONVENTION §8.8).
 */
@Hidden
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/routes")
@RequiredArgsConstructor
public class InternalRouteController {

	private final ManageRouteUseCase manageRouteUseCase;

	@Value("${GATEWAY_INTERNAL_SECRET:dev-secret}")
	private String internalSecret;

	/**
	 * enabled=true 라우트 전량 반환 (게이트웨이 초기 로드용)
	 *
	 * <p>{@code X-Internal-Secret} 헤더를 {@link MessageDigest#isEqual}로 상수시간 비교하여 타이밍 공격을 방지한다. 시크릿 값은
	 * 로그에 남기지 않는다.
	 *
	 * @param secret X-Internal-Secret 헤더
	 * @return 활성화된 라우트 목록 또는 403 Forbidden
	 */
	@GetMapping
	public ResponseEntity<InternalRouteListResponse> listEnabledRoutes(
			@RequestHeader(value = "X-Internal-Secret", required = false) String secret) {
		if (!constantTimeEquals(secret, internalSecret)) {
			log.warn("Internal route request rejected: invalid X-Internal-Secret");
			return ResponseEntity.status(403).build();
		}

		List<RouteResult> results = manageRouteUseCase.listEnabledRoutes();
		List<RouteDto> dtos =
				results.stream()
						.map(r -> new RouteDto(r.routeId(), r.pathPrefix(), r.upstreamUrl(), r.enabled()))
						.toList();

		return ResponseEntity.ok(new InternalRouteListResponse(dtos));
	}

	/**
	 * 상수시간 문자열 비교 (타이밍 공격 방지)
	 *
	 * <p>null 또는 길이가 다른 경우도 안전하게 처리한다.
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

	/**
	 * 내부 라우트 목록 응답
	 *
	 * @param routes 활성화된 라우트 DTO 목록
	 */
	public record InternalRouteListResponse(List<RouteDto> routes) {}

	/**
	 * 내부 라우트 DTO
	 *
	 * @param routeId 라우트 UUID 문자열
	 * @param pathPrefix 경로 접두사
	 * @param upstreamUrl 업스트림 서비스 URL
	 * @param enabled 활성화 여부
	 */
	public record RouteDto(String routeId, String pathPrefix, String upstreamUrl, boolean enabled) {}
}
