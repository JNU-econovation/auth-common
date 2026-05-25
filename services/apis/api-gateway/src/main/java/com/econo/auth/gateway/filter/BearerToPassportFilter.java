package com.econo.auth.gateway.filter;

import com.econo.auth.gateway.config.GatewayRoutingConfig;
import com.econo.auth.gateway.security.JwtVerifier;
import com.econo.auth.gateway.security.PassportBuilder;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bearer 토큰 → Passport 헤더 주입 GlobalFilter
 *
 * <p>{@code Authorization: Bearer <token>} 헤더에서 SAS 발급 JWT를 추출하고, {@link
 * JwtVerifier}(ReactiveJwtDecoder)로 RS256 서명 검증 후 {@link PassportBuilder}로 Passport 직렬화하여 {@code
 * X-User-Passport} 헤더에 주입한다. SAS OAuth 엔드포인트({@code /oauth2/**}, {@code /.well-known/**}, {@code
 * /userinfo}) 및 인증 불필요 경로는 Bearer 토큰 검증 없이 통과시킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BearerToPassportFilter implements GlobalFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String PASSPORT_HEADER = "X-User-Passport";

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

	/** Authorization 헤더에서 Bearer 토큰 추출 */
	private Optional<String> extractBearerToken(ServerWebExchange exchange) {
		String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
			return Optional.of(authHeader.substring(BEARER_PREFIX.length()));
		}
		return Optional.empty();
	}

	/** 인증 필요 경로 판별 */
	private boolean isProtectedPath(String path) {
		return routingConfig.permittedPaths().stream().noneMatch(path::startsWith);
	}

	/** 401 Unauthorized 응답 */
	private Mono<Void> rejectUnauthorized(ServerWebExchange exchange) {
		exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
		return exchange.getResponse().setComplete();
	}
}
