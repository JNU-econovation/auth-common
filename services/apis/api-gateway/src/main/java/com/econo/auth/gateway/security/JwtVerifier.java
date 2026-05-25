package com.econo.auth.gateway.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이 JWT 서명·만료·iss 검증 컴포넌트 — RSA/JWKS 기반 (RS256)
 *
 * <p>Spring Bean 사용 시 {@link com.econo.auth.gateway.config.GatewaySecurityConfig}에서 {@code
 * AUTH_JWKS_URI} / {@code AUTH_ISSUER_URI} 환경변수로 생성한다. 테스트에서는 JWK JSON 문자열 + issuer URI 생성자({@code
 * new JwtVerifier(jwkJson, issuerUri)})를 통해 WireMock 없이 직접 검증기를 생성할 수 있다.
 *
 * <h2>issuer 검증</h2>
 *
 * <p>{@link JwtValidators#createDefaultWithIssuer(String)}를 사용하여 exp/nbf 타임스탬프와 {@code iss} 클레임을 함께
 * 검증한다. {@code iss} 불일치 시 {@link org.springframework.security.oauth2.jwt.JwtValidationException}을
 * 발행한다.
 */
@Slf4j
public class JwtVerifier {

	private final ReactiveJwtDecoder jwtDecoder;

	/**
	 * JWK JSON 문자열 기반 생성자 — JWKS URI fetch 없이 로컬 공개키로 검증
	 *
	 * <p>단일 공개키 JWK JSON ({@code rsaKey.toPublicJWK().toJSONString()}) 또는 JWK Set JSON 모두 허용한다. 테스트에서
	 * WireMock 스텁 없이 RSA 공개키를 직접 사용할 때 이 생성자를 사용한다.
	 *
	 * @param jwkJson JWK 또는 JWK Set JSON 문자열
	 * @param issuerUri JWT {@code iss} 클레임과 비교할 발급자 URI (예: {@code https://auth.econo.com})
	 */
	public JwtVerifier(String jwkJson, String issuerUri) {
		this.jwtDecoder = buildDecoderFromJwkJson(jwkJson, issuerUri);
	}

	/**
	 * JWKS URI 기반 팩토리 메서드 — Spring Bean 사용 시 {@link
	 * com.econo.auth.gateway.config.GatewaySecurityConfig}에서 호출
	 *
	 * <p>{@link NimbusReactiveJwtDecoder}가 JWKS 캐시 및 키 자동 갱신(rotation)을 내장한다. {@code jwksUri}는
	 * auth-api 내부 주소를 직접 가리켜야 한다 (자기참조 루프 방지).
	 *
	 * @param jwksUri JWKS 엔드포인트 URI
	 * @param issuerUri JWT {@code iss} 클레임과 비교할 발급자 URI
	 * @return JWKS URI 기반 {@link JwtVerifier} 인스턴스
	 */
	public static JwtVerifier fromJwksUri(String jwksUri, String issuerUri) {
		return new UriMode(jwksUri, issuerUri);
	}

	/**
	 * JWT 검증 후 Jwt 반환 (비동기 Mono)
	 *
	 * @param token JWT 문자열
	 * @return 검증된 {@link Jwt} — 실패 시 {@link org.springframework.security.oauth2.jwt.BadJwtException}
	 *     또는 {@link org.springframework.security.oauth2.jwt.JwtValidationException} 발행
	 */
	public Mono<Jwt> verify(String token) {
		return jwtDecoder.decode(token);
	}

	/**
	 * JWK JSON 문자열 → ReactiveJwtDecoder 빌드 (네트워크 호출 없음)
	 *
	 * <p>{@link NimbusReactiveJwtDecoder#withJwkSource(java.util.function.Function)}를 사용하여 로컬 {@link
	 * JWKSet}에서 키를 조회한다. JWT 헤더의 kid 값으로 키를 선택하며, 일치하는 키가 없으면 전체 키셋을 반환한다. 빌드 후 {@link
	 * JwtValidators#createDefaultWithIssuer(String)}로 iss 클레임 검증을 활성화한다.
	 */
	private static ReactiveJwtDecoder buildDecoderFromJwkJson(String jwkJson, String issuerUri) {
		try {
			final JWKSet jwkSet;
			if (jwkJson.trim().startsWith("{") && jwkJson.contains("\"keys\"")) {
				jwkSet = JWKSet.parse(jwkJson);
			} else {
				JWK singleJwk = JWK.parse(jwkJson);
				jwkSet = new JWKSet(singleJwk);
			}

			NimbusReactiveJwtDecoder decoder =
					NimbusReactiveJwtDecoder.withJwkSource(
									signedJwt -> {
										String keyId = signedJwt.getHeader().getKeyID();
										JWKSelector selector =
												new JWKSelector(new JWKMatcher.Builder().keyID(keyId).build());
										List<JWK> selected = selector.select(jwkSet);
										if (selected.isEmpty()) {
											// kid 없거나 매칭 키 없으면 전체 키셋으로 재시도
											return Flux.fromIterable(jwkSet.getKeys());
										}
										return Flux.fromIterable(selected);
									})
							.build();
			decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
			return decoder;
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize JwtVerifier from JWK JSON", e);
		}
	}

	/** JWKS URI 기반 내부 서브클래스 */
	private static final class UriMode extends JwtVerifier {
		UriMode(String jwksUri, String issuerUri) {
			super(buildUriDecoder(jwksUri, issuerUri));
		}

		/** JWKS URI 기반 디코더 빌드 후 issuer 검증 validator 설정 */
		private static ReactiveJwtDecoder buildUriDecoder(String jwksUri, String issuerUri) {
			NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build();
			decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
			return decoder;
		}
	}

	/** ReactiveJwtDecoder를 직접 주입받는 내부 생성자 */
	private JwtVerifier(ReactiveJwtDecoder decoder) {
		this.jwtDecoder = decoder;
	}
}
