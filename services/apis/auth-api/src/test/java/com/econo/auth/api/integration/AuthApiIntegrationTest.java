package com.econo.auth.api.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * auth-api 통합 테스트 — signup 흐름 유지 (SAS 재작업 후)
 *
 * <p>변경 사항: - 로그인 후 Set-Cookie(auth_token) 관련 테스트 제거 (HMAC/쿠키 방식 폐기) - 미가입 loginId 로그인 401 테스트 →
 * SasAuthorizationServerIntegrationTest로 이관 - signup 관련 테스트만 유지
 *
 * <p>plan: todo.md [POST /api/v1/auth/signup] — 기존 구현 유지, 변경 없음
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AuthApiIntegrationTest {

	@Container
	static PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>("postgres:16")
					.withDatabaseName("auth_test")
					.withUsername("test")
					.withPassword("test");

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		// plan: JWT_SECRET, auth.jwt.* 환경변수 제거됨 (HMAC 방식 폐기)
		// plan: SAS RSA 키 설정
		registry.add(
				"RSA_PRIVATE_KEY",
				() -> SasAuthorizationServerIntegrationTest.TestRsaKeys.TEST_PRIVATE_KEY_PEM);
		registry.add(
				"RSA_PUBLIC_KEY",
				() -> SasAuthorizationServerIntegrationTest.TestRsaKeys.TEST_PUBLIC_KEY_PEM);
		registry.add("AUTH_ISSUER_URI", () -> "http://localhost:8080");
		registry.add("auth.frontend-login-url", () -> "http://localhost:3000/login");
		registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
		registry.add("FIRST_PARTY_CLIENT_ID", () -> "econo-spa");
		registry.add("FIRST_PARTY_REDIRECT_URI", () -> "http://localhost:3000/callback");
	}

	@Autowired private MockMvc mockMvc;

	@Nested
	@DisplayName("POST /api/v1/auth/signup 통합 테스트")
	class SignupIntegrationTest {

		@Test
		@DisplayName("가입 성공 시 201 반환")
		void signupSuccess() throws Exception {
			// given
			String signupBody =
					"""
					{
						"name": "홍길동",
						"loginId": "integration_user01",
						"password": "Econo1234!",
						"generation": 32,
						"status": "AM"
					}
					""";

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(signupBody))
					.andExpect(status().isCreated());
		}

		@Test
		@DisplayName("중복 loginId로 가입 시도하면 409 반환")
		void signupWithDuplicateLoginIdReturns409() throws Exception {
			// given
			String signupBody =
					"""
					{
						"name": "홍길동",
						"loginId": "dup_integration_user",
						"password": "Econo1234!",
						"generation": 32,
						"status": "AM"
					}
					""";
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(signupBody))
					.andExpect(status().isCreated());

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(signupBody))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.errorCode").value("MEMBER_ALREADY_EXISTS"));
		}
	}

	@Nested
	@DisplayName("POST /api/v1/auth/login — HMAC 쿠키 방식 폐기 확인")
	class LoginHmacCookieRemovedTest {

		@Test
		@DisplayName("로그인 성공 시 auth_token 쿠키가 더 이상 발급되지 않는다 (HMAC 방식 폐기)")
		void loginSuccessDoesNotReturnAuthTokenCookie() throws Exception {
			// given — 사전 가입
			String signupBody =
					"""
					{
						"name": "홍길동",
						"loginId": "hmac_removed_user",
						"password": "Econo1234!",
						"generation": 32,
						"status": "AM"
					}
					""";
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(signupBody))
					.andExpect(status().isCreated());

			String loginBody =
					"""
					{
						"loginId": "hmac_removed_user",
						"password": "Econo1234!"
					}
					""";

			// when
			// plan: implementation-plan.md — MemberController.login() 제거, LoginService 제거
			// JsonLoginAuthenticationFilter가 처리하며 SESSION 쿠키 발급 (auth_token 아님)
			org.springframework.test.web.servlet.MvcResult loginResult =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(loginBody))
							.andExpect(status().isOk())
							.andReturn();

			// then
			String setCookieHeader = loginResult.getResponse().getHeader("Set-Cookie");
			// plan: auth_token 쿠키 대신 SESSION 쿠키 발급
			if (setCookieHeader != null) {
				assertThat(setCookieHeader).doesNotStartWith("auth_token=");
				assertThat(setCookieHeader).startsWith("SESSION=");
			}
		}
	}
}
