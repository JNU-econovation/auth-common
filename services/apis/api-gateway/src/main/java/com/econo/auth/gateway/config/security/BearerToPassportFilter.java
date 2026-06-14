package com.econo.auth.gateway.config.security;

import com.econo.auth.gateway.config.GatewayRoutingConfig;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Bearer 토큰 → Passport 헤더 주입 GlobalFilter
 *
 * <p>AT 추출 우선순위:
 *
 * <ol>
 *   <li>{@code Authorization: Bearer <token>} 헤더 (APP / 서버 간 호출)
 *   <li>{@code Cookie: at=<token>} (WEB 브라우저 — HttpOnly 쿠키)
 * </ol>
 *
 * <p>인바운드 {@code X-User-Passport} 헤더는 위조 방지를 위해 경로·토큰 유무에 관계없이 항상 제거된다.
 *
 * <p>미토큰 요청은 경로 무관 passthrough한다. 인증 강제(401 거부)는 다운스트림 {@code @PassportAuth}(econo-passport)에 위임한다.
 *
 * <p>{@code permitted-paths}는 <b>무효 토큰 요청의 통과/거부 분기에서만</b> 사용한다. 미토큰 요청 분기에서는 참조하지 않는다.
 *
 * <p>패턴 평가는 Spring의 {@link PathPatternParser}를 사용하여 {@code /oauth2-hack} 같은 오탐을 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BearerToPassportFilter implements GlobalFilter, Ordered {

	/** RouteToRequestUrlFilter(10000)보다 먼저 실행 */
	@Override
	public int getOrder() {
		return -1;
	}

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String PASSPORT_HEADER = "X-User-Passport";
	private static final PathPatternParser PATTERN_PARSER = new PathPatternParser();

	private final JwtVerifier jwtVerifier;
	private final PassportBuilder passportBuilder;
	private final GatewayRoutingConfig routingConfig;

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// 1. 인바운드 X-User-Passport 항상 제거 (위조 방지) — 모든 분기 공통 base
		ServerWebExchange strippedExchange =
				exchange.mutate().request(r -> r.headers(h -> h.remove(PASSPORT_HEADER))).build();

		String path = exchange.getRequest().getPath().value();
		Optional<String> tokenOptional = extractBearerToken(exchange);

		// 2. 토큰 없음 → isProtectedPath 체크 없이 무조건 passthrough
		if (tokenOptional.isEmpty()) {
			log.debug("No bearer token, passing through, path={}", path);
			return chain.filter(strippedExchange);
		}

		// 3. 토큰 있음 → 검증
		// verify가 malformed 토큰에 동기 예외를 던질 수 있어 defer로 감싸 리액티브 에러 경로로 일원화
		String token = tokenOptional.get();
		return Mono.defer(() -> jwtVerifier.verify(token))
				.flatMap(
						jwt -> {
							// 4. 유효 → strip된 base에 검증 Passport 주입
							String encodedPassport = passportBuilder.buildAndSerialize(jwt);
							ServerWebExchange mutatedExchange =
									strippedExchange
											.mutate()
											.request(r -> r.header(PASSPORT_HEADER, encodedPassport))
											.build();
							return chain.filter(mutatedExchange);
						})
				.onErrorResume(
						e -> {
							// 5. 무효 → 보호 경로면 401, permitted면 strip된 base로 pass
							log.warn("JWT verification failed, path={}, error={}", path, e.getMessage());
							if (isProtectedPath(path)) {
								return rejectUnauthorized(strippedExchange);
							}
							return chain.filter(strippedExchange);
						});
	}

	private Optional<String> extractBearerToken(ServerWebExchange exchange) {
		String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
			return Optional.of(authHeader.substring(BEARER_PREFIX.length()));
		}
		var atCookie = exchange.getRequest().getCookies().getFirst("at");
		if (atCookie != null && !atCookie.getValue().isBlank()) {
			return Optional.of(atCookie.getValue());
		}
		return Optional.empty();
	}

	/**
	 * 인증 불필요 경로 판별 — {@link PathPatternParser}로 Ant 패턴 매칭.
	 *
	 * <p>단순 {@code startsWith}와 달리 {@code /oauth2/**} 패턴은 {@code /oauth2/} 이하만 매칭하여 오탐을 방지한다.
	 */
	private boolean isProtectedPath(String path) {
		List<String> patterns = routingConfig.permittedPaths();
		PathContainer pathContainer = PathContainer.parsePath(path);
		return patterns.stream()
				.noneMatch(
						pattern -> {
							try {
								PathPattern parsed = PATTERN_PARSER.parse(pattern);
								return parsed.matches(pathContainer);
							} catch (Exception e) {
								return false;
							}
						});
	}

	private Mono<Void> rejectUnauthorized(ServerWebExchange exchange) {
		exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
		return exchange.getResponse().setComplete();
	}
}
