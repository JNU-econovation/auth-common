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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
		registry.add("JWT_SECRET", () -> "integration-test-secret-key-which-is-long-enough-32bytes");
		registry.add("auth.jwt.expiry-seconds", () -> "3600");
		registry.add("auth.jwt.cookie-name", () -> "auth_token");
	}

	@Autowired private MockMvc mockMvc;

	@Nested
	@DisplayName("가입 → 로그인 통합 흐름 테스트")
	class SignupLoginFlowTest {

		@Test
		@DisplayName("가입 후 로그인 시 Set-Cookie 헤더가 포함된 200 응답을 받는다")
		void signupThenLoginReceivesCookie() throws Exception {
			// given
			String signupBody =
					"""
					{
						"name": "홍길동",
						"loginId": "flow_user01",
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
						"loginId": "flow_user01",
						"password": "Econo1234!"
					}
					""";

			// when
			MvcResult loginResult =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(loginBody))
							.andExpect(status().isOk())
							.andReturn();

			// then
			String setCookieHeader = loginResult.getResponse().getHeader("Set-Cookie");
			assertThat(setCookieHeader)
					.isNotNull()
					.startsWith("auth_token=")
					.containsIgnoringCase("HttpOnly")
					.containsIgnoringCase("Secure")
					.containsIgnoringCase("SameSite=Strict");
		}

		@Test
		@DisplayName("중복 loginId로 가입 시도하면 409 반환")
		void signupWithDuplicateLoginIdReturns409() throws Exception {
			// given
			String signupBody =
					"""
					{
						"name": "홍길동",
						"loginId": "duplicate_user",
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

		@Test
		@DisplayName("미가입 loginId로 로그인 시도하면 401 반환")
		void loginWithNonExistingLoginIdReturns401() throws Exception {
			// given
			String loginBody =
					"""
					{
						"loginId": "nonexistent_user",
						"password": "Econo1234!"
					}
					""";

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
		}
	}
}
