package com.econo.auth.gateway.security;

import static org.assertj.core.api.Assertions.*;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JwtVerifierTest {

	private static final String JWT_SECRET =
			"test-secret-key-which-is-long-enough-for-hmac-sha256-algorithm";
	private static final String WRONG_SECRET =
			"wrong-secret-key-which-is-long-enough-for-hmac-sha256-algorithm";

	private JwtVerifier jwtVerifier;

	@BeforeEach
	void setUp() {
		jwtVerifier = new JwtVerifier(JWT_SECRET);
	}

	/** 테스트용 유효한 JWT 생성 헬퍼 */
	private String buildValidJwt() {
		SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
		return Jwts.builder()
				.subject("42")
				.claim("loginId", "honggildong")
				.claim("name", "홍길동")
				.claim("generation", 32)
				.claim("status", "AM")
				.claim("roles", List.of("USER"))
				.issuedAt(new Date())
				.expiration(new Date(System.currentTimeMillis() + 3600_000L))
				.signWith(key)
				.compact();
	}

	/** 테스트용 만료된 JWT 생성 헬퍼 */
	private String buildExpiredJwt() {
		SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
		return Jwts.builder()
				.subject("42")
				.claim("loginId", "honggildong")
				.claim("name", "홍길동")
				.issuedAt(new Date(System.currentTimeMillis() - 7200_000L))
				.expiration(new Date(System.currentTimeMillis() - 3600_000L))
				.signWith(key)
				.compact();
	}

	/** 테스트용 서명 오류 JWT 생성 헬퍼 */
	private String buildTamperedJwt() {
		SecretKey wrongKey = Keys.hmacShaKeyFor(WRONG_SECRET.getBytes(StandardCharsets.UTF_8));
		return Jwts.builder()
				.subject("42")
				.claim("loginId", "honggildong")
				.issuedAt(new Date())
				.expiration(new Date(System.currentTimeMillis() + 3600_000L))
				.signWith(wrongKey)
				.compact();
	}

	@Nested
	@DisplayName("JWT 검증 성공 테스트")
	class VerifySuccessTest {

		@Test
		@DisplayName("유효한 JWT 검증 시 Claims를 반환한다")
		void verifyValidJwtReturnsClaims() {
			// given
			String jwt = buildValidJwt();

			// when
			Claims claims = jwtVerifier.verify(jwt);

			// then
			assertThat(claims).isNotNull();
			assertThat(claims.getSubject()).isEqualTo("42");
			assertThat(claims.get("loginId", String.class)).isEqualTo("honggildong");
			assertThat(claims.get("name", String.class)).isEqualTo("홍길동");
			assertThat(claims.get("generation", Integer.class)).isEqualTo(32);
			assertThat(claims.get("status", String.class)).isEqualTo("AM");
		}
	}

	@Nested
	@DisplayName("JWT 검증 실패 테스트")
	class VerifyFailTest {

		@Test
		@DisplayName("만료된 JWT 검증 시 JwtException 발생")
		void verifyExpiredJwtThrowsException() {
			// given
			String expiredJwt = buildExpiredJwt();

			// when & then
			assertThatThrownBy(() -> jwtVerifier.verify(expiredJwt)).isInstanceOf(JwtException.class);
		}

		@Test
		@DisplayName("서명이 잘못된 JWT 검증 시 JwtException 발생")
		void verifyTamperedJwtThrowsException() {
			// given
			String tamperedJwt = buildTamperedJwt();

			// when & then
			assertThatThrownBy(() -> jwtVerifier.verify(tamperedJwt)).isInstanceOf(JwtException.class);
		}

		@Test
		@DisplayName("빈 문자열 JWT 검증 시 예외 발생")
		void verifyBlankJwtThrowsException() {
			// when & then
			assertThatThrownBy(() -> jwtVerifier.verify("")).isInstanceOf(Exception.class);
		}

		@Test
		@DisplayName("유효하지 않은 형식의 JWT 검증 시 예외 발생")
		void verifyMalformedJwtThrowsException() {
			// when & then
			assertThatThrownBy(() -> jwtVerifier.verify("not.a.valid.jwt.format"))
					.isInstanceOf(Exception.class);
		}
	}
}
