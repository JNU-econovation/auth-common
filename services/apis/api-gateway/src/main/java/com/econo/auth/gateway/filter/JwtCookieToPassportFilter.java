package com.econo.auth.gateway.filter;

import com.econo.auth.gateway.config.GatewayRoutingConfig;
import com.econo.auth.gateway.security.JwtVerifier;
import com.econo.auth.gateway.security.PassportBuilder;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** JWT 쿠키 → Passport 헤더 주입 GlobalFilter */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtCookieToPassportFilter implements GlobalFilter {

	private static final String AUTH_TOKEN_COOKIE = "auth_token";
	private static final String PASSPORT_HEADER = "X-User-Passport";

	private final JwtVerifier jwtVerifier;
	private final PassportBuilder passportBuilder;
	private final GatewayRoutingConfig routingConfig;

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String path = exchange.getRequest().getPath().value();
		Optional<String> jwtOptional = extractCookie(exchange);

		if (jwtOptional.isEmpty()) {
			if (isProtectedPath(path)) {
				log.warn("auth_token cookie missing, path={}", path);
				return rejectUnauthorized(exchange);
			}
			return chain.filter(exchange);
		}

		String jwt = jwtOptional.get();
		try {
			Claims claims = jwtVerifier.verify(jwt);
			String encodedPassport = passportBuilder.buildAndSerialize(claims);
			ServerWebExchange mutatedExchange =
					exchange.mutate().request(r -> r.header(PASSPORT_HEADER, encodedPassport)).build();
			return chain.filter(mutatedExchange);
		} catch (ExpiredJwtException e) {
			log.warn("JWT expired, path={}", path);
			if (isProtectedPath(path)) return rejectUnauthorized(exchange);
			return chain.filter(exchange);
		} catch (JwtException e) {
			log.error("JWT signature invalid, path={}", path);
			if (isProtectedPath(path)) return rejectUnauthorized(exchange);
			return chain.filter(exchange);
		}
	}

	/** auth_token 쿠키 추출 */
	private Optional<String> extractCookie(ServerWebExchange exchange) {
		HttpCookie cookie = exchange.getRequest().getCookies().getFirst(AUTH_TOKEN_COOKIE);
		return Optional.ofNullable(cookie).map(HttpCookie::getValue);
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
