package com.econo.auth.gateway.filter;

import com.econo.auth.gateway.config.GatewayRoutingConfig;
import com.econo.auth.gateway.security.JwtVerifier;
import com.econo.auth.gateway.security.PassportBuilder;
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
 * <p>인증 불필요 경로는 {@link GatewayRoutingConfig#permittedPaths()}에서 Ant 패턴으로 관리한다. 패턴 평가는 Spring의
 * {@link PathPatternParser}를 사용하여 {@code /oauth2-hack} 같은 오탐을 방지한다.
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
		String path = exchange.getRequest().getPath().value();
		Optional<String> tokenOptional = extractBearerToken(exchange);

		if (tokenOptional.isEmpty()) {
			if (isProtectedPath(path)) {
				log.warn("Bearer token missing, path={}", path);
				return rejectUnauthorized(exchange);
			}
			return chain.filter(exchange);
		}

		String token = tokenOptional.get();
		return jwtVerifier
				.verify(token)
				.flatMap(
						jwt -> {
							String encodedPassport = passportBuilder.buildAndSerialize(jwt);
							ServerWebExchange mutatedExchange =
									exchange
											.mutate()
											.request(r -> r.header(PASSPORT_HEADER, encodedPassport))
											.build();
							return chain.filter(mutatedExchange);
						})
				.onErrorResume(
						e -> {
							log.warn("JWT verification failed, path={}, error={}", path, e.getMessage());
							if (isProtectedPath(path)) {
								return rejectUnauthorized(exchange);
							}
							return chain.filter(exchange);
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
