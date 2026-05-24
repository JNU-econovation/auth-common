package com.econo.auth.gateway.filter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import com.econo.auth.gateway.config.GatewayRoutingConfig;
import com.econo.auth.gateway.security.JwtVerifier;
import com.econo.auth.gateway.security.PassportBuilder;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class JwtCookieToPassportFilterTest {

	@Mock private JwtVerifier jwtVerifier;

	@Mock private PassportBuilder passportBuilder;

	@Mock private Claims claims;

	@Mock private GatewayRoutingConfig routingConfig;

	private JwtCookieToPassportFilter filter;

	@BeforeEach
	void setUp() {
		org.mockito.Mockito.lenient()
				.when(routingConfig.permittedPaths())
				.thenReturn(List.of("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/logout"));
		filter = new JwtCookieToPassportFilter(jwtVerifier, passportBuilder, routingConfig);
	}

	@Nested
	@DisplayName("쿠키 있음 + 검증 성공 테스트")
	class CookiePresentAndValidTest {

		@Test
		@DisplayName("유효한 JWT 쿠키가 있으면 X-User-Passport 헤더가 주입된다")
		void validCookieInjectsPassportHeader() {
			// given
			String validJwt = "valid.jwt.token";
			String encodedPassport = "base64encodedPassport";
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/api/v1/some/resource")
							.cookie(new HttpCookie("auth_token", validJwt))
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			given(jwtVerifier.verify(validJwt)).willReturn(claims);
			given(passportBuilder.buildAndSerialize(claims)).willReturn(encodedPassport);

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

			result.block();
		}
	}

	@Nested
	@DisplayName("쿠키 없음 테스트")
	class NoCookieTest {

		@Test
		@DisplayName("쿠키가 없고 인증 불필요 경로이면 Passport 헤더 미설정 후 통과")
		void noCookieOnPermittedPathPassesThrough() {
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

			result.block();
		}

		@Test
		@DisplayName("쿠키가 없고 인증 필요 경로이면 401 반환")
		void noCookieOnProtectedPathReturns401() {
			// given — 인증이 필요한 일반 경로
			MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/some/resource").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// when
			filter.filter(exchange, chain -> Mono.empty()).block();

			// then
			assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("쿠키가 없고 signup 경로이면 통과")
		void noCookieOnSignupPathPassesThrough() {
			// given
			MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/signup").build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			// when
			filter.filter(exchange, ex -> Mono.empty()).block();

			// then — 401이 아님을 검증 (경로 통과)
			assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		}
	}

	@Nested
	@DisplayName("JWT 검증 실패 테스트")
	class JwtVerificationFailTest {

		@Test
		@DisplayName("JWT 서명 오류 시 인증 필요 경로에서 401 반환")
		void signatureErrorOnProtectedPathReturns401() {
			// given
			String invalidJwt = "invalid.jwt.token";
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/api/v1/some/resource")
							.cookie(new HttpCookie("auth_token", invalidJwt))
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			given(jwtVerifier.verify(invalidJwt)).willThrow(new JwtException("Invalid signature"));

			// when
			filter.filter(exchange, chain -> Mono.empty()).block();

			// then
			assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("JWT 만료 시 인증 필요 경로에서 401 반환")
		void expiredJwtOnProtectedPathReturns401() {
			// given
			String expiredJwt = "expired.jwt.token";
			MockServerHttpRequest request =
					MockServerHttpRequest.get("/api/v1/some/resource")
							.cookie(new HttpCookie("auth_token", expiredJwt))
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			given(jwtVerifier.verify(expiredJwt)).willThrow(new JwtException("JWT expired"));

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
							.cookie(new HttpCookie("auth_token", invalidJwt))
							.build();
			MockServerWebExchange exchange = MockServerWebExchange.from(request);

			given(jwtVerifier.verify(invalidJwt)).willThrow(new JwtException("Invalid signature"));

			// when
			filter.filter(exchange, ex -> Mono.empty()).block();

			// then
			assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
		}
	}
}
