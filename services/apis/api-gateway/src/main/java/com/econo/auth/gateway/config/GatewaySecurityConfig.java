package com.econo.auth.gateway.config;

import com.econo.auth.gateway.security.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Gateway 보안 설정 — JwtVerifier 빈 등록
 *
 * <p>{@code AUTH_JWKS_URI} 환경변수(auth-api 내부 주소)를 사용하여 JWKS URI 기반 {@link JwtVerifier}를 생성한다.
 * Gateway 공개 URL을 사용하면 자기참조 루프가 발생하므로 반드시 auth-api 내부 주소를 지정해야 한다.
 *
 * <h2>JwtDecoder 빈 중복 방지</h2>
 *
 * <p>{@link JwtVerifier}가 {@link org.springframework.security.oauth2.jwt.ReactiveJwtDecoder}를 직접
 * 생성·보유하는 단일 소스(Single Source of Truth)로 유지한다. {@code application.yml}에 {@code
 * spring.security.oauth2.resourceserver.jwt.*}를 두지 않아 Spring Boot oauth2-resource-server
 * auto-configuration이 트리거되지 않게 한다 — 현재 {@code application.yml}에 해당 설정이 없으므로 auto-config 빈 충돌이 발생하지
 * 않는다. 이 클래스의 책임은 {@code AUTH_JWKS_URI} 환경변수를 주입하여 {@link JwtVerifier} 빈을 단독으로 제공하는 것이다.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

	@Value("${AUTH_JWKS_URI:http://localhost:8081/oauth2/jwks}")
	private String jwksUri;

	@Value("${AUTH_ISSUER_URI:http://localhost:8080}")
	private String issuerUri;

	/**
	 * JwtVerifier 빈 등록 — JWKS URI 기반 RSA 서명 검증 + {@code iss} 클레임 검증
	 *
	 * <p>{@code AUTH_ISSUER_URI}(SAS issuer = Gateway 공개 URL)를 주입하여 발급자가 일치하지 않는 토큰을 거부한다.
	 *
	 * @return {@link JwtVerifier} 인스턴스
	 */
	@Bean
	public JwtVerifier jwtVerifier() {
		return JwtVerifier.fromJwksUri(jwksUri, issuerUri);
	}

	/** BearerToPassportFilter가 실질적 인증을 담당하므로 Spring Security 레이어는 전체 허용 */
	@Bean
	public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
		return http
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.authorizeExchange(ex -> ex.anyExchange().permitAll())
				.build();
	}
}
