package com.econo.auth.gateway.config.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import com.econo.auth.gateway.config.GatewayRoutingConfig;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * BearerToPassportFilter 재작성 테스트 — 쿠키 기반 → Bearer 헤더 기반
 *
 * <p>plan: todo.md [JwtCookieToPassportFilterTest 재작성] — 쿠키 기반 → Bearer 헤더 기반 테스트 plan:
 * implementation-plan.md [BearerToPassportFilter] — Authorization: Bearer 헤더에서 JWT 추출 plan:
 * api-design-plan.md — Bearer 토큰 없음/만료/서명오류 시 보호경로 401, permit 경로 통과
 *
 * <p>구현 단계 현행 문서 확인: BearerToPassportFilter (기존 JwtCookieToPassportFilter 재작성) 클래스명 확인
 */
@ExtendWith(MockitoExtension.class)
class BearerToPassportFilterTest {

	@Mock private JwtVerifier jwtVerifier;

	@Mock private PassportBuilder passportBuilder;

	@Mock private GatewayRoutingConfig routingConfig;

	// plan에서 결정된 클래스명: BearerToPassportFilter
	private BearerToPassportFilter filter;

	private static RSAKey rsaKey;
	private static NimbusJwtEncoder jwtEncoder;

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
		lenient()
				.when(routingConfig.permittedPaths())
				.thenReturn(
						List.of(
								"/api/v1/auth/signup",
								"/api/v1/auth/login",
								"/api/v1/auth/logout",
								"/oauth2/**",
								"/.well-known/**",
								"/userinfo"));
		filter = new BearerToPassportFilter(jwtVerifier, passportBuilder, routingConfig);
	}

	/** 테스트용 RS256 JWT 생성 헬퍼 */
	private String buildRsaJwt() {
		JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsaKey.getKeyID()).build();
		JwtClaimsSet claims =
				JwtClaimsSet.builder()
						.subject("42")
						.issuer("https://auth.econo.com")
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

	@Nested
	@DisplayName("Bearer 헤더 있음 + 검증 성공 테스트")
	class BearerPresentAndValidTest {

		@Test
		@DisplayName("유효한 Bearer JWT가 있으면 X-User-Passport 헤더가 주입된다")
		void validBearerTokenInjectsPassportHeader() {
			// given
			String validJwt = buildRsaJwt();
			String encodedPassport = "base64encodedPassport";
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/api/v1/some/resource")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + validJwt)
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// plan: JwtVerifier는 Mono<Jwt>를 반환 (ReactiveJwtDecoder 기반)
			Jwt mockJwt = mock(Jwt.class);
			given(jwtVerifier.verify(validJwt)).willReturn(Mono.just(mockJwt));
			given(passportBuilder.buildAndSerialize(mockJwt)).willReturn(encodedPassport);

			// when
			Mono<Void> result =
					filter.filter(
							exchange,
							ex -> {
								// then
								assertThat(ex.getRequest().getHeaders().getFirst("X-User-Passport"))
										.isEqualTo(encodedPassport);
								return Mono.empty();
							});

			StepVerifier.create(result).verifyComplete();
		}

		@Test
		@DisplayName("Bearer 접두사 없는 Authorization 헤더는 미토큰으로 취급되어 보호경로에서도 통과된다")
		void authorizationHeaderWithoutBearerPrefixPassesThroughOnProtectedPath() {
			// given — Basic 인증 헤더는 Bearer 추출 실패 → 미토큰 취급 → passthrough (신규 동작)
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/api/v1/some/resource")
							.header(HttpHeaders.AUTHORIZATION, "Basic someCredentials")
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// when
			filter.filter(exchange, chain -> Mono.empty()).block();

			// then — 새 동작: 미토큰은 경로 무관 passthrough → 401 아님
			assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		}
	}

	@Nested
	@DisplayName("Bearer 헤더 없음 테스트")
	class NoBearerTokenTest {

		@Test
		@DisplayName("미토큰은 경로 종류와 무관하게 통과 — permitted 경로 예시(login), Passport 헤더 미주입")
		void noBearerOnPermittedPathPassesThrough() {
			// given — /api/v1/auth/login은 permit 경로
			MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// when
			Mono<Void> result =
					filter.filter(
							exchange,
							ex -> {
								// then
								assertThat(ex.getRequest().getHeaders().get("X-User-Passport")).isNull();
								return Mono.empty();
							});

			StepVerifier.create(result).verifyComplete();
		}

		@Test
		@DisplayName("미토큰 + 보호경로 → passthrough (401 아님, Passport 미주입)")
		void noBearerOnProtectedPathPassesThrough() {
			// given — Bearer 없음, 보호 경로 (신규 동작: 경로 무관 passthrough)
			MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/some/resource").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// when
			Mono<Void> result =
					filter.filter(
							exchange,
							ex -> {
								// then — chain 호출됨, Passport 헤더 없음
								assertThat(ex.getRequest().getHeaders().get("X-User-Passport")).isNull();
								return Mono.empty();
							});

			StepVerifier.create(result).verifyComplete();

			// 응답 상태도 401 아님
			assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("미토큰은 경로 종류와 무관하게 통과 — permitted 경로 예시(signup)")
		void noBearerOnSignupPathPassesThrough() {
			// given
			MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/signup").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// when
			filter.filter(exchange, ex -> Mono.empty()).block();

			// then — 401이 아님 검증
			assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("미토큰은 경로 종류와 무관하게 통과 — permitted 경로 예시(/oauth2/**)")
		void noBearerOnOauth2PathPassesThrough() {
			// given — /oauth2/** 경로는 permit (SAS 엔드포인트)
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/oauth2/authorize?response_type=code").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// when
			filter.filter(exchange, ex -> Mono.empty()).block();

			// then
			assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("미토큰은 경로 종류와 무관하게 통과 — permitted 경로 예시(/.well-known/**)")
		void noBearerOnWellKnownPathPassesThrough() {
			// given
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/.well-known/openid-configuration").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// when
			filter.filter(exchange, ex -> Mono.empty()).block();

			// then
			assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName(
				"미토큰 + 동적 prefix 경로(/eeos/guest/programs) → passthrough (permitted-paths 불일치 오 401 방지)")
		void noBearerOnDynamicPrefixGuestPathPassesThrough() {
			// given — 동적 라우팅 환경: 게이트웨이는 prefix 재작성 전 원시 경로를 봄
			// /eeos/guest/programs 는 permitted-paths 패턴(/api/guest/**)과 불일치 → 구 동작에서 오 401 발생
			// 신규 동작: 미토큰이면 경로 무관 passthrough
			MockServerHttpRequest request = MockServerHttpRequest.get("/eeos/guest/programs").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// when
			Mono<Void> result =
					filter.filter(
							exchange,
							ex -> {
								// then — chain 호출됨, Passport 헤더 없음 (동적 prefix 경로도 동일)
								assertThat(ex.getRequest().getHeaders().get("X-User-Passport")).isNull();
								return Mono.empty();
							});

			StepVerifier.create(result).verifyComplete();

			// 응답 상태도 401 아님
			assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		}
	}

	@Nested
	@DisplayName("JWT 검증 실패 테스트")
	class JwtVerificationFailTest {

		@Test
		@DisplayName("서명 오류 JWT 전달 시 인증 필요 경로에서 401 반환")
		void signatureErrorOnProtectedPathReturns401() {
			// given
			String invalidJwt = "invalid.jwt.token";
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/api/v1/some/resource")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + invalidJwt)
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// plan: BadJwtException이 Spring Security oauth2-resource-server 예외
			given(jwtVerifier.verify(invalidJwt))
					.willReturn(
							Mono.error(
									new org.springframework.security.oauth2.jwt.BadJwtException(
											"Invalid signature")));

			// when
			filter.filter(exchange, chain -> Mono.empty()).block();

			// then
			assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("만료된 JWT 전달 시 인증 필요 경로에서 401 반환")
		void expiredJwtOnProtectedPathReturns401() {
			// given
			String expiredJwt = "expired.jwt.token";
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/api/v1/some/resource")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredJwt)
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// plan: 만료 시 JwtValidationException 또는 BadJwtException — Spring Security
			// oauth2-resource-server
			given(jwtVerifier.verify(expiredJwt))
					.willReturn(
							Mono.error(
									new org.springframework.security.oauth2.jwt.BadJwtException("JWT expired")));

			// when
			filter.filter(exchange, chain -> Mono.empty()).block();

			// then
			assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("형식이 깨진 토큰 + 보호경로 → 401 (verify 동기 예외가 defer로 리액티브 에러 경로 처리)")
		void malformedTokenOnProtectedPathReturns401() {
			// given
			String malformedToken = "not.a.valid.jwt";
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/api/v1/some/resource")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + malformedToken)
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// verify()가 동기적으로 예외를 던지는 상황 — Mono.defer 없이는 500/예외 전파, defer 후 onError로 처리
			when(jwtVerifier.verify(any())).thenThrow(new BadJwtException("malformed"));

			// when
			filter.filter(exchange, chain -> Mono.empty()).block();

			// then
			assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("JWT 검증 실패라도 인증 불필요 경로이면 통과한다")
		void jwtFailureOnPermittedPathPassesThrough() {
			// given
			String invalidJwt = "invalid.jwt.token";
			MockServerHttpRequest request =
					MockServerHttpRequest.post("/api/v1/auth/login")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + invalidJwt)
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			given(jwtVerifier.verify(invalidJwt))
					.willReturn(
							Mono.error(
									new org.springframework.security.oauth2.jwt.BadJwtException(
											"Invalid signature")));

			// when
			filter.filter(exchange, ex -> Mono.empty()).block();

			// then
			assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		}
	}

	@Nested
	@DisplayName("쿠키 기반 인증 방식 제거 확인 테스트")
	class CookieBasedAuthRemovedTest {

		@Test
		@DisplayName("auth_token 쿠키만 있고 Bearer 헤더가 없으면 미토큰 취급되어 보호경로에서도 통과된다")
		void cookieOnlyWithoutBearerOnProtectedPathPassesThrough() {
			// given — 기존 auth_token 쿠키 방식은 더 이상 지원되지 않음
			// 신규 동작: 미토큰 → 경로 무관 passthrough
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/api/v1/some/resource")
							.cookie(new org.springframework.http.HttpCookie("auth_token", "some.jwt.token"))
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// when
			filter.filter(exchange, chain -> Mono.empty()).block();

			// then — 새 동작: 미토큰은 경로 무관 passthrough → 401 아님
			assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		}
	}

	@Nested
	@DisplayName("인바운드 X-User-Passport 위조 방지 테스트")
	class InboundPassportStripTest {

		@Test
		@DisplayName("위조 X-User-Passport + 미토큰 → strip 후 통과 (다운스트림에 Passport 없음)")
		void inboundForgeryHeaderIsStrippedOnNoToken() {
			// given — 클라이언트가 X-User-Passport 헤더를 위조, Bearer 없음
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/api/v1/some/resource")
							.header("X-User-Passport", "forged-passport-value")
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// when
			Mono<Void> result =
					filter.filter(
							exchange,
							ex -> {
								// then — 위조 헤더가 strip됨: 다운스트림에 X-User-Passport 없음
								assertThat(ex.getRequest().getHeaders().get("X-User-Passport")).isNull();
								return Mono.empty();
							});

			StepVerifier.create(result).verifyComplete();

			// 401도 아님 (passthrough)
			assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("위조 X-User-Passport + 유효 토큰 → 검증 Passport로 교체 (위조값 헤더 목록에서 완전 제거)")
		void inboundForgeryHeaderIsReplacedByVerifiedPassportOnValidToken() {
			// given — 클라이언트가 X-User-Passport 위조, 동시에 유효한 Bearer 토큰 전달
			String validJwt = buildRsaJwt();
			String encodedPassport = "verified-passport-from-builder";
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/api/v1/some/resource")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + validJwt)
							.header("X-User-Passport", "forged-passport-value")
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			Jwt mockJwt = mock(Jwt.class);
			given(jwtVerifier.verify(validJwt)).willReturn(Mono.just(mockJwt));
			given(passportBuilder.buildAndSerialize(mockJwt)).willReturn(encodedPassport);

			// when
			Mono<Void> result =
					filter.filter(
							exchange,
							ex -> {
								// then — 헤더 값 목록 전체를 검사: 검증된 값만 있어야 하고 위조값은 없어야 함
								// [핵심] strip 없이 header.add만 하면 위조값이 헤더 목록에 남는다.
								// strip 선행(인바운드 제거 후 set)이어야만 이 단언이 통과한다.
								List<String> passportValues = ex.getRequest().getHeaders().get("X-User-Passport");
								assertThat(passportValues)
										.as("다운스트림에 전달된 X-User-Passport 헤더 값 목록")
										.containsExactly(encodedPassport); // 정확히 검증 값 하나만 있어야 함
								assertThat(passportValues).doesNotContain("forged-passport-value");
								return Mono.empty();
							});

			StepVerifier.create(result).verifyComplete();
		}
	}
}
