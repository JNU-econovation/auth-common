package com.econo.auth.gateway.security;

import static org.assertj.core.api.Assertions.*;

import com.econo.common.auth.core.passport.Passport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * PassportBuilder 재작성 테스트 — Spring Security Jwt 타입 기반
 *
 * <p>plan: todo.md [PassportBuilder 재확인] — SAS JWT 클레임 구조에 맞게 클레임 매핑 확인 plan:
 * implementation-plan.md [PassportBuilder] — Claims(jjwt) → Jwt(Spring Security) 타입 변경 plan:
 * api-design-plan.md — Passport 변환 클레임 매핑 표 (sub→memberId, loginId, name, generation, status,
 * roles)
 */
class PassportBuilderTest {

	private static RSAKey rsaKey;
	private static NimbusJwtEncoder jwtEncoder;
	private PassportBuilder passportBuilder;
	private ObjectMapper objectMapper;

	@BeforeAll
	static void setUpKeys() throws Exception {
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

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		passportBuilder = new PassportBuilder(objectMapper);
	}

	/**
	 * 테스트용 Spring Security Jwt 생성 헬퍼
	 *
	 * <p>plan: api-design-plan.md Passport 변환 클레임 매핑 (memberId, loginId, name, generation, status,
	 * roles)
	 */
	private Jwt buildJwt(
			String memberId,
			String loginId,
			String name,
			int generation,
			String status,
			List<String> roles) {
		JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsaKey.getKeyID()).build();
		JwtClaimsSet claimsSet =
				JwtClaimsSet.builder()
						.subject(memberId)
						.issuer("https://auth.econo.com")
						.audience(List.of("econo-spa"))
						.issuedAt(Instant.now())
						.expiresAt(Instant.now().plusSeconds(3600))
						.claim("memberId", Long.parseLong(memberId))
						.claim("loginId", loginId)
						.claim("name", name)
						.claim("generation", generation)
						.claim("status", status)
						.claim("roles", roles)
						.build();
		// plan: PassportBuilder.buildPassport(Jwt) — Spring Security Jwt 타입 파라미터
		String tokenValue =
				jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claimsSet)).getTokenValue();
		// Jwt 객체를 직접 파싱하여 반환 (구현 단계 현행 문서 확인: Jwt 생성 방식)
		return Jwt.withTokenValue(tokenValue)
				.header("alg", "RS256")
				.subject(memberId)
				.issuer("https://auth.econo.com")
				.audience(List.of("econo-spa"))
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(3600))
				.claim("memberId", Long.parseLong(memberId))
				.claim("loginId", loginId)
				.claim("name", name)
				.claim("generation", generation)
				.claim("status", status)
				.claim("roles", roles)
				.build();
	}

	@Nested
	@DisplayName("Spring Security Jwt 클레임 → Passport 변환 테스트")
	class BuildPassportFromSpringJwtTest {

		@Test
		@DisplayName("Spring Security Jwt에서 Passport를 올바르게 생성한다")
		void buildPassportFromSpringJwt() throws Exception {
			// given
			// plan: PassportBuilder.buildAndSerialize(Jwt jwt) — 기존 Claims → Jwt 타입으로 시그니처 변경
			Jwt jwt = buildJwt("42", "honggildong", "홍길동", 32, "AM", List.of("USER"));

			// when
			String encoded = passportBuilder.buildAndSerialize(jwt);

			// then — Base64 디코딩 후 Passport로 역직렬화
			byte[] decoded = Base64.getDecoder().decode(encoded);
			Passport passport = objectMapper.readValue(decoded, Passport.class);

			assertThat(passport.getMemberId()).isEqualTo(42L);
			assertThat(passport.getLoginId()).isEqualTo("honggildong");
			assertThat(passport.getName()).isEqualTo("홍길동");
			assertThat(passport.getGeneration()).isEqualTo(32);
			assertThat(passport.getStatus()).isEqualTo("AM");
			assertThat(passport.getRoles()).containsExactly("USER");
		}

		@Test
		@DisplayName("sub 클레임이 Passport memberId(Long)으로 변환된다")
		void subClaimIsMappedToMemberIdAsLong() throws Exception {
			// given
			// plan: api-design-plan.md — sub 클레임 → memberId: Long.valueOf(claims.getSubject())
			Jwt jwt = buildJwt("42", "honggildong", "홍길동", 32, "AM", List.of("USER"));

			// when
			String encoded = passportBuilder.buildAndSerialize(jwt);
			byte[] decoded = Base64.getDecoder().decode(encoded);
			Passport passport = objectMapper.readValue(decoded, Passport.class);

			// then
			assertThat(passport.getMemberId()).isEqualTo(42L);
			assertThat(passport.getMemberId()).isInstanceOf(Long.class);
		}

		@Test
		@DisplayName("직렬화 결과는 유효한 Base64 문자열이다")
		void serializedResultIsValidBase64() {
			// given
			Jwt jwt = buildJwt("42", "honggildong", "홍길동", 32, "AM", List.of("USER"));

			// when
			String encoded = passportBuilder.buildAndSerialize(jwt);

			// then
			assertThatNoException().isThrownBy(() -> Base64.getDecoder().decode(encoded));
		}

		@Test
		@DisplayName("loginId 커스텀 클레임이 Passport loginId로 직접 매핑된다")
		void loginIdClaimIsMappedDirectly() throws Exception {
			// given
			// plan: api-design-plan.md — loginId 커스텀 클레임 직접 매핑 (기존 유지)
			Jwt jwt = buildJwt("10", "my_login_id", "테스터", 1, "RM", List.of("USER"));

			// when
			String encoded = passportBuilder.buildAndSerialize(jwt);
			byte[] decoded = Base64.getDecoder().decode(encoded);
			Passport passport = objectMapper.readValue(decoded, Passport.class);

			// then
			assertThat(passport.getLoginId()).isEqualTo("my_login_id");
		}

		@Test
		@DisplayName("generation과 status 커스텀 클레임이 Passport에 포함된다")
		void generationAndStatusClaimsAreIncluded() throws Exception {
			// given
			Jwt jwt = buildJwt("99", "user99", "사용자", 50, "CM", List.of("USER"));

			// when
			String encoded = passportBuilder.buildAndSerialize(jwt);
			byte[] decoded = Base64.getDecoder().decode(encoded);
			Passport passport = objectMapper.readValue(decoded, Passport.class);

			// then
			assertThat(passport.getGeneration()).isEqualTo(50);
			assertThat(passport.getStatus()).isEqualTo("CM");
		}

		@Test
		@DisplayName("roles 커스텀 클레임이 Passport roles 리스트로 매핑된다")
		void rolesClaimIsMappedToPassportRoles() throws Exception {
			// given
			Jwt jwt = buildJwt("1", "admin_user", "어드민", 1, "AM", List.of("USER", "ADMIN"));

			// when
			String encoded = passportBuilder.buildAndSerialize(jwt);
			byte[] decoded = Base64.getDecoder().decode(encoded);
			Passport passport = objectMapper.readValue(decoded, Passport.class);

			// then
			assertThat(passport.getRoles()).containsExactlyInAnyOrder("USER", "ADMIN");
		}

		@Test
		@DisplayName("iat/exp 클레임이 Passport issuedAt/expiresAt으로 변환된다")
		void iatAndExpClaimsAreMappedToLocalDateTime() throws Exception {
			// given
			Jwt jwt = buildJwt("42", "honggildong", "홍길동", 32, "AM", List.of("USER"));

			// when
			String encoded = passportBuilder.buildAndSerialize(jwt);
			byte[] decoded = Base64.getDecoder().decode(encoded);
			Passport passport = objectMapper.readValue(decoded, Passport.class);

			// then
			// plan: implementation-plan.md — Instant → LocalDateTime 변환 (기존 Date → 변경)
			assertThat(passport.getIssuedAt()).isNotNull();
			assertThat(passport.getExpiresAt()).isNotNull();
			assertThat(passport.getExpiresAt()).isAfter(passport.getIssuedAt());
		}
	}

	@Nested
	@DisplayName("PassportBuilder 구 시그니처 제거 확인 테스트")
	class OldSignatureRemovedTest {

		@Test
		@DisplayName("buildAndSerialize는 Spring Security Jwt 타입을 파라미터로 받는다")
		void buildAndSerializeAcceptsSpringSecurityJwt() {
			// given
			Jwt jwt = buildJwt("42", "honggildong", "홍길동", 32, "AM", List.of("USER"));

			// when & then
			// plan: implementation-plan.md — buildAndSerialize(Jwt jwt) 시그니처 — jjwt Claims 임포트 완전 제거
			assertThatNoException().isThrownBy(() -> passportBuilder.buildAndSerialize(jwt));
		}
	}
}
