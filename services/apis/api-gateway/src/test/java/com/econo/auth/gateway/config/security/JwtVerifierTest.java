package com.econo.auth.gateway.config.security;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * JwtVerifier 재작성 테스트 — RSA/JWKS 기반 서명 검증
 *
 * <p>plan: todo.md [JwtVerifierTest 재작성] — HMAC 기반 → RSA/JWKS 기반으로 교체 plan: implementation-plan.md
 * [JwtVerifier 재작성] — ReactiveJwtDecoder (JWKS URI) 사용
 *
 * <p>구현 단계 현행 문서 확인: NimbusReactiveJwtDecoder.withJwkSetUri() API (SAS 1.x / Spring Security 6.x)
 */
class JwtVerifierTest {

	private static final String TEST_ISSUER = "https://auth.econo.com";

	private static RSAKey rsaKey;
	private static NimbusJwtEncoder jwtEncoder;

	@BeforeAll
	static void setUpKeys() throws Exception {
		// given — RSA 키페어 직접 생성 (테스트용)
		KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
		keyPairGenerator.initialize(2048);
		KeyPair keyPair = keyPairGenerator.generateKeyPair();

		rsaKey =
				new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
						.privateKey((RSAPrivateKey) keyPair.getPrivate())
						.keyID(UUID.randomUUID().toString())
						.build();

		JWKSet jwkSet = new JWKSet(rsaKey);
		ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext> jwkSource =
				new ImmutableJWKSet<>(jwkSet);
		jwtEncoder = new NimbusJwtEncoder(jwkSource);
	}

	/** 테스트용 유효한 RS256 JWT 생성 헬퍼 */
	private String buildValidRsaJwt() {
		// 구현 단계 현행 문서 확인: JwsHeader.with(SignatureAlgorithm.RS256) Spring Security 6.x API
		JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsaKey.getKeyID()).build();
		JwtClaimsSet claims =
				JwtClaimsSet.builder()
						.subject("42")
						.issuer(TEST_ISSUER)
						.audience(List.of("econo-spa"))
						.issuedAt(Instant.now())
						.expiresAt(Instant.now().plusSeconds(3600))
						.claim("memberId", 42L)
						.claim("loginId", "honggildong")
						.claim("name", "홍길동")
						.claim("generation", 32)
						.claim("status", "AM")
						.claim("roles", List.of("USER"))
						.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
	}

	/** 테스트용 만료된 RS256 JWT 생성 헬퍼 */
	private String buildExpiredRsaJwt() {
		JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsaKey.getKeyID()).build();
		JwtClaimsSet claims =
				JwtClaimsSet.builder()
						.subject("42")
						.issuer(TEST_ISSUER)
						.audience(List.of("econo-spa"))
						.issuedAt(Instant.now().minusSeconds(7200))
						.expiresAt(Instant.now().minusSeconds(3600))
						.claim("loginId", "honggildong")
						.claim("name", "홍길동")
						.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
	}

	/** 지정한 issuer로 서명된 RS256 JWT 생성 헬퍼 (iss 검증 테스트용) */
	private String buildRsaJwtWithIssuer(String issuer) {
		JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsaKey.getKeyID()).build();
		JwtClaimsSet claims =
				JwtClaimsSet.builder()
						.subject("42")
						.issuer(issuer)
						.audience(List.of("econo-spa"))
						.issuedAt(Instant.now())
						.expiresAt(Instant.now().plusSeconds(3600))
						.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
	}

	@Nested
	@DisplayName("RSA 서명 검증 성공 테스트")
	class VerifySuccessTest {

		@Test
		@DisplayName("유효한 RS256 JWT 검증 시 Jwt 객체를 반환한다")
		void verifyValidRsaJwtReturnsJwt() {
			// given
			String jwt = buildValidRsaJwt();
			// 구현 단계 현행 문서 확인: JwtVerifier는 ReactiveJwtDecoder를 사용하므로 Mono<Jwt> 반환
			JwtVerifier jwtVerifier = new JwtVerifier(rsaKey.toPublicJWK().toJSONString(), TEST_ISSUER);

			// when
			Jwt result = jwtVerifier.verify(jwt).block();

			// then
			assertThat(result).isNotNull();
			assertThat(result.getSubject()).isEqualTo("42");
			assertThat(result.getClaimAsString("loginId")).isEqualTo("honggildong");
			assertThat(result.getClaimAsString("name")).isEqualTo("홍길동");
			assertThat((Object) result.getClaim("generation")).isNotNull();
			assertThat(result.getClaimAsString("status")).isEqualTo("AM");
			assertThat(result.getClaimAsStringList("roles")).containsExactly("USER");
		}

		@Test
		@DisplayName("유효한 RS256 JWT의 커스텀 클레임 memberId가 포함된다")
		void verifyValidRsaJwtContainsMemberIdClaim() {
			// given
			String jwt = buildValidRsaJwt();
			JwtVerifier jwtVerifier = new JwtVerifier(rsaKey.toPublicJWK().toJSONString(), TEST_ISSUER);

			// when
			Jwt result = jwtVerifier.verify(jwt).block();

			// then
			assertThat(result).isNotNull();
			assertThat((Object) result.getClaim("memberId")).isNotNull();
		}
	}

	@Nested
	@DisplayName("RSA 서명 검증 실패 테스트")
	class VerifyFailTest {

		@Test
		@DisplayName("만료된 RS256 JWT 검증 시 JwtValidationException 발생")
		void verifyExpiredRsaJwtThrowsException() throws Exception {
			// given
			String expiredJwt = buildExpiredRsaJwt();
			JwtVerifier jwtVerifier = new JwtVerifier(rsaKey.toPublicJWK().toJSONString(), TEST_ISSUER);

			// when & then
			// 구현 단계 현행 문서 확인: ReactiveJwtDecoder는 Mono<Jwt>를 반환하며 에러는 onErrorMap으로 처리
			assertThatThrownBy(() -> jwtVerifier.verify(expiredJwt).block())
					.isInstanceOf(JwtValidationException.class);
		}

		@Test
		@DisplayName("다른 RSA 키로 서명된 JWT 검증 시 BadJwtException 발생")
		void verifyJwtSignedWithDifferentKeyThrowsException() throws Exception {
			// given — 다른 키페어로 서명된 JWT 생성
			KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
			gen.initialize(2048);
			KeyPair anotherKeyPair = gen.generateKeyPair();
			RSAKey anotherRsaKey =
					new RSAKey.Builder((RSAPublicKey) anotherKeyPair.getPublic())
							.privateKey((RSAPrivateKey) anotherKeyPair.getPrivate())
							.keyID(UUID.randomUUID().toString())
							.build();
			JWKSet anotherJwkSet = new JWKSet(anotherRsaKey);
			ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext> anotherSource =
					new ImmutableJWKSet<>(anotherJwkSet);
			NimbusJwtEncoder anotherEncoder = new NimbusJwtEncoder(anotherSource);

			JwsHeader jwsHeader =
					JwsHeader.with(SignatureAlgorithm.RS256).keyId(anotherRsaKey.getKeyID()).build();
			JwtClaimsSet claimsSet =
					JwtClaimsSet.builder()
							.subject("42")
							.issuedAt(Instant.now())
							.expiresAt(Instant.now().plusSeconds(3600))
							.build();
			String tamperedJwt =
					anotherEncoder.encode(JwtEncoderParameters.from(jwsHeader, claimsSet)).getTokenValue();

			// 원래 키 검증기로 검증 시 실패 예상
			JwtVerifier jwtVerifier = new JwtVerifier(rsaKey.toPublicJWK().toJSONString(), TEST_ISSUER);

			// when & then
			assertThatThrownBy(() -> jwtVerifier.verify(tamperedJwt).block())
					.isInstanceOf(Exception.class);
		}

		@Test
		@DisplayName("빈 문자열 JWT 검증 시 예외 발생")
		void verifyBlankJwtThrowsException() {
			// given
			JwtVerifier jwtVerifier = new JwtVerifier(rsaKey.toPublicJWK().toJSONString(), TEST_ISSUER);

			// when & then
			assertThatThrownBy(() -> jwtVerifier.verify("").block()).isInstanceOf(Exception.class);
		}

		@Test
		@DisplayName("issuer(iss)가 일치하지 않는 JWT 검증 시 JwtValidationException 발생")
		void verifyJwtWithWrongIssuerThrowsException() {
			// given — 잘못된 issuer로 서명된 (서명·만료는 유효한) JWT
			String wrongIssuerJwt = buildRsaJwtWithIssuer("https://evil.example.com");
			JwtVerifier jwtVerifier = new JwtVerifier(rsaKey.toPublicJWK().toJSONString(), TEST_ISSUER);

			// when & then
			assertThatThrownBy(() -> jwtVerifier.verify(wrongIssuerJwt).block())
					.isInstanceOf(JwtValidationException.class);
		}

		@Test
		@DisplayName("HMAC 서명 JWT를 RSA 검증기에 넣으면 예외 발생")
		void verifyHmacJwtWithRsaVerifierThrowsException() {
			// given — 기존 HMAC JWT는 RSA 검증기에서 실패해야 함
			String hmacJwt =
					"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" + ".eyJzdWIiOiI0MiJ9" + ".some-hmac-signature";
			JwtVerifier jwtVerifier = new JwtVerifier(rsaKey.toPublicJWK().toJSONString(), TEST_ISSUER);

			// when & then
			assertThatThrownBy(() -> jwtVerifier.verify(hmacJwt).block()).isInstanceOf(Exception.class);
		}
	}
}
