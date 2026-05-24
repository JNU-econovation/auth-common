package com.econo.auth.gateway.security;

import static org.assertj.core.api.Assertions.*;

import com.econo.common.auth.core.passport.Passport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PassportSerializerTest {

	private static final String JWT_SECRET =
			"test-secret-key-which-is-long-enough-for-hmac-sha256-algorithm";

	private PassportBuilder passportBuilder;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		passportBuilder = new PassportBuilder(objectMapper);
	}

	/** 테스트용 Claims 생성 헬퍼 */
	private Claims buildClaims(
			String memberId,
			String loginId,
			String name,
			int generation,
			String status,
			List<String> roles) {
		SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
		String jwt =
				Jwts.builder()
						.subject(memberId)
						.claim("loginId", loginId)
						.claim("name", name)
						.claim("generation", generation)
						.claim("status", status)
						.claim("roles", roles)
						.issuedAt(new Date())
						.expiration(new Date(System.currentTimeMillis() + 3600_000L))
						.signWith(key)
						.compact();
		return Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt).getPayload();
	}

	@Nested
	@DisplayName("JWT 클레임 → Passport 변환 테스트")
	class BuildPassportTest {

		@Test
		@DisplayName("JWT 클레임에서 Passport를 올바르게 생성한다")
		void buildPassportFromClaims() throws Exception {
			// given
			Claims claims = buildClaims("42", "honggildong", "홍길동", 32, "AM", List.of("USER"));

			// when
			String encoded = passportBuilder.buildAndSerialize(claims);

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
		@DisplayName("직렬화 결과는 유효한 Base64 문자열이다")
		void serializedResultIsValidBase64() {
			// given
			Claims claims = buildClaims("42", "honggildong", "홍길동", 32, "AM", List.of("USER"));

			// when
			String encoded = passportBuilder.buildAndSerialize(claims);

			// then
			assertThatNoException().isThrownBy(() -> Base64.getDecoder().decode(encoded));
		}

		@Test
		@DisplayName("JWT 클레임 loginId가 Passport loginId로 직접 매핑된다")
		void loginIdIsMappedDirectly() throws Exception {
			// given
			Claims claims = buildClaims("10", "my_login_id", "테스터", 1, "RM", List.of("USER"));

			// when
			String encoded = passportBuilder.buildAndSerialize(claims);
			byte[] decoded = Base64.getDecoder().decode(encoded);
			Passport passport = objectMapper.readValue(decoded, Passport.class);

			// then
			assertThat(passport.getLoginId()).isEqualTo("my_login_id");
		}

		@Test
		@DisplayName("generation과 status 클레임이 Passport에 포함된다")
		void generationAndStatusAreIncluded() throws Exception {
			// given
			Claims claims = buildClaims("99", "user99", "사용자", 50, "CM", List.of("USER"));

			// when
			String encoded = passportBuilder.buildAndSerialize(claims);
			byte[] decoded = Base64.getDecoder().decode(encoded);
			Passport passport = objectMapper.readValue(decoded, Passport.class);

			// then
			assertThat(passport.getGeneration()).isEqualTo(50);
			assertThat(passport.getStatus()).isEqualTo("CM");
		}
	}
}
