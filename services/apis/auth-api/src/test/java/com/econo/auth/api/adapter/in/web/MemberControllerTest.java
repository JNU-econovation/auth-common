package com.econo.auth.api.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.auth.api.config.SecurityConfig;
import com.econo.auth.core.member.application.port.in.LoginUseCase;
import com.econo.auth.core.member.application.port.in.LoginUseCase.LoginResult;
import com.econo.auth.core.member.application.port.in.SignupUseCase;
import com.econo.auth.core.member.exception.InvalidCredentialsException;
import com.econo.auth.core.member.exception.InvalidPasswordPolicyException;
import com.econo.auth.core.member.exception.MemberAlreadyExistsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(MemberController.class)
@Import(SecurityConfig.class)
class MemberControllerTest {

	@Autowired private MockMvc mockMvc;

	@MockBean private SignupUseCase signupUseCase;

	@MockBean private LoginUseCase loginUseCase;

	@Nested
	@DisplayName("POST /api/v1/auth/signup 테스트")
	class SignupTest {

		@Test
		@DisplayName("유효한 요청으로 가입 성공 시 201 반환")
		void signupSuccess() throws Exception {
			// given
			String requestBody =
					"""
					{
						"name": "홍길동",
						"loginId": "honggildong",
						"password": "Econo1234!",
						"generation": 32,
						"status": "AM"
					}
					""";
			willDoNothing().given(signupUseCase).signup(any());

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isCreated());
		}

		@Test
		@DisplayName("loginId 형식 위반 시 400 VALIDATION_FAILED 반환")
		void signupWithInvalidLoginId() throws Exception {
			// given
			String requestBody =
					"""
					{
						"name": "홍길동",
						"loginId": "ab",
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
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("name이 빈 문자열이면 400 VALIDATION_FAILED 반환")
		void signupWithEmptyName() throws Exception {
			// given
			String requestBody =
					"""
					{
						"name": "",
						"loginId": "honggildong",
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
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("generation이 범위 외(0)이면 400 VALIDATION_FAILED 반환")
		void signupWithInvalidGeneration() throws Exception {
			// given
			String requestBody =
					"""
					{
						"name": "홍길동",
						"loginId": "honggildong",
						"password": "Econo1234!",
						"generation": 0,
						"status": "AM"
					}
					""";

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("비밀번호 정책 위반 시 400 INVALID_PASSWORD_POLICY 반환")
		void signupWithInvalidPasswordPolicy() throws Exception {
			// given
			String requestBody =
					"""
					{
						"name": "홍길동",
						"loginId": "honggildong",
						"password": "Econo1234!",
						"generation": 32,
						"status": "AM"
					}
					""";
			willThrow(InvalidPasswordPolicyException.of("대문자 누락")).given(signupUseCase).signup(any());

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("INVALID_PASSWORD_POLICY"));
		}

		@Test
		@DisplayName("loginId 중복 시 409 MEMBER_ALREADY_EXISTS 반환")
		void signupWithDuplicateLoginId() throws Exception {
			// given
			String requestBody =
					"""
					{
						"name": "홍길동",
						"loginId": "honggildong",
						"password": "Econo1234!",
						"generation": 32,
						"status": "AM"
					}
					""";
			willThrow(MemberAlreadyExistsException.of("honggildong")).given(signupUseCase).signup(any());

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.errorCode").value("MEMBER_ALREADY_EXISTS"));
		}

		@Test
		@DisplayName("필수 필드 누락 시 400 VALIDATION_FAILED 반환")
		void signupWithMissingRequiredField() throws Exception {
			// given — password 누락
			String requestBody =
					"""
					{
						"name": "홍길동",
						"loginId": "honggildong",
						"generation": 32,
						"status": "AM"
					}
					""";

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}
	}

	@Nested
	@DisplayName("POST /api/v1/auth/login 테스트")
	class LoginTest {

		@Test
		@DisplayName("로그인 성공 시 200 + Set-Cookie 헤더 포함")
		void loginSuccess() throws Exception {
			// given
			String requestBody =
					"""
					{
						"loginId": "honggildong",
						"password": "Econo1234!"
					}
					""";
			given(loginUseCase.login(any()))
					.willReturn(new LoginResult("eyJhbGciOiJIUzI1NiJ9.testToken"));

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/login")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isOk())
					.andExpect(header().exists("Set-Cookie"));
		}

		@Test
		@DisplayName("로그인 성공 쿠키는 HttpOnly 속성을 포함한다")
		void loginSuccessCookieIsHttpOnly() throws Exception {
			// given
			String requestBody =
					"""
					{
						"loginId": "honggildong",
						"password": "Econo1234!"
					}
					""";
			given(loginUseCase.login(any()))
					.willReturn(new LoginResult("eyJhbGciOiJIUzI1NiJ9.testToken"));

			// when
			MvcResult result =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(requestBody))
							.andExpect(status().isOk())
							.andReturn();

			// then
			String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
			assertThat(setCookieHeader).containsIgnoringCase("HttpOnly");
		}

		@Test
		@DisplayName("로그인 성공 쿠키는 Secure 속성을 포함한다")
		void loginSuccessCookieIsSecure() throws Exception {
			// given
			String requestBody =
					"""
					{
						"loginId": "honggildong",
						"password": "Econo1234!"
					}
					""";
			given(loginUseCase.login(any()))
					.willReturn(new LoginResult("eyJhbGciOiJIUzI1NiJ9.testToken"));

			// when
			MvcResult result =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(requestBody))
							.andExpect(status().isOk())
							.andReturn();

			// then
			String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
			assertThat(setCookieHeader).containsIgnoringCase("Secure");
		}

		@Test
		@DisplayName("로그인 성공 쿠키는 SameSite=Strict 속성을 포함한다")
		void loginSuccessCookieHasSameSiteStrict() throws Exception {
			// given
			String requestBody =
					"""
					{
						"loginId": "honggildong",
						"password": "Econo1234!"
					}
					""";
			given(loginUseCase.login(any()))
					.willReturn(new LoginResult("eyJhbGciOiJIUzI1NiJ9.testToken"));

			// when
			MvcResult result =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(requestBody))
							.andExpect(status().isOk())
							.andReturn();

			// then
			String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
			assertThat(setCookieHeader).containsIgnoringCase("SameSite=Strict");
		}

		@Test
		@DisplayName("로그인 성공 쿠키 이름은 auth_token이다")
		void loginSuccessCookieNameIsAuthToken() throws Exception {
			// given
			String requestBody =
					"""
					{
						"loginId": "honggildong",
						"password": "Econo1234!"
					}
					""";
			given(loginUseCase.login(any()))
					.willReturn(new LoginResult("eyJhbGciOiJIUzI1NiJ9.testToken"));

			// when
			MvcResult result =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(requestBody))
							.andExpect(status().isOk())
							.andReturn();

			// then
			String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
			assertThat(setCookieHeader).startsWith("auth_token=");
		}

		@Test
		@DisplayName("로그인 성공 응답 바디에 JWT가 노출되지 않는다")
		void loginSuccessResponseBodyDoesNotContainJwt() throws Exception {
			// given
			String requestBody =
					"""
					{
						"loginId": "honggildong",
						"password": "Econo1234!"
					}
					""";
			String jwtToken = "eyJhbGciOiJIUzI1NiJ9.secretToken";
			given(loginUseCase.login(any())).willReturn(new LoginResult(jwtToken));

			// when
			MvcResult result =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.content(requestBody))
							.andExpect(status().isOk())
							.andReturn();

			// then
			assertThat(result.getResponse().getContentAsString()).doesNotContain(jwtToken);
		}

		@Test
		@DisplayName("loginId 또는 비밀번호가 틀리면 401 INVALID_CREDENTIALS 반환")
		void loginWithInvalidCredentials() throws Exception {
			// given
			String requestBody =
					"""
					{
						"loginId": "honggildong",
						"password": "wrongPassword!"
					}
					""";
			willThrow(InvalidCredentialsException.of()).given(loginUseCase).login(any());

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/login")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
		}

		@Test
		@DisplayName("loginId 필드가 비어 있으면 400 VALIDATION_FAILED 반환")
		void loginWithBlankLoginId() throws Exception {
			// given
			String requestBody =
					"""
					{
						"loginId": "",
						"password": "Econo1234!"
					}
					""";

			// when & then
			mockMvc
					.perform(
							post("/api/v1/auth/login")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}
	}

	@Nested
	@DisplayName("POST /api/v1/auth/logout 테스트")
	class LogoutTest {

		@Test
		@DisplayName("로그아웃 시 200 반환")
		void logoutSuccess() throws Exception {
			// when & then
			mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isOk());
		}

		@Test
		@DisplayName("로그아웃 시 auth_token 쿠키가 Max-Age=0으로 만료된다")
		void logoutExpiresCookie() throws Exception {
			// when
			MvcResult result =
					mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isOk()).andReturn();

			// then
			String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
			assertThat(setCookieHeader).containsIgnoringCase("Max-Age=0").startsWith("auth_token=");
		}
	}
}
