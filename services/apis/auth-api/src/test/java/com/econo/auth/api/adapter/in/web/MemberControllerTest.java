package com.econo.auth.api.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.auth.api.config.SecurityConfig;
import com.econo.auth.member.application.port.in.SignupUseCase;
import com.econo.auth.member.exception.InvalidPasswordPolicyException;
import com.econo.auth.member.exception.MemberAlreadyExistsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MemberController 웹 레이어 테스트 — SAS 재작업 후
 *
 * <p>plan: todo.md [MemberController.login 재작성] — login() 메서드 제거, JsonLoginAuthenticationFilter가 처리
 * plan: todo.md [MemberController.logout 재작성] — 세션 무효화 + SESSION 쿠키 만료로 재작성 plan: todo.md [POST
 * /api/v1/auth/signup] — 기존 구현 유지, 변경 없음
 *
 * <p>LoginUseCase 의존 제거: plan implementation-plan.md — LoginUseCase/LoginService 제거
 */
@WebMvcTest(MemberController.class)
@Import(SecurityConfig.class)
class MemberControllerTest {

	@Autowired private MockMvc mockMvc;

	@MockBean private SignupUseCase signupUseCase;

	// plan: implementation-plan.md — LoginUseCase 제거됨. LoginUseCase MockBean 제거.

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

	// 로그아웃은 ReissueController로 이전 — MemberController 범위 밖
	// ReissueControllerTest에서 별도 검증

	@Nested
	@DisplayName("POST /api/v1/auth/login 핸들러 부재 확인 테스트")
	class LoginHandlerRemovedTest {

		@Test
		@DisplayName("MemberController에 login() 핸들러가 없으므로 @WebMvcTest 슬라이스에서 404 반환")
		void loginEndpointHasNoControllerHandler() throws Exception {
			// plan: implementation-plan.md — MemberController.login() 메서드 삭제
			// JsonLoginAuthenticationFilter가 POST /api/v1/auth/login 경로를 필터 레벨에서 처리하므로
			// MemberController에는 login() 핸들러 메서드가 없다.
			// @WebMvcTest 슬라이스는 필터 체인을 완전히 구성하지 않으므로 JsonLoginAuthenticationFilter가
			// 동작하지 않아, 컨트롤러 핸들러 부재가 404로 드러난다.
			// (통합 환경에서는 필터가 선점하여 200 + Set-Cookie(SESSION) 반환)
			String requestBody =
					"""
					{
						"loginId": "honggildong",
						"password": "Econo1234!"
					}
					""";
			mockMvc
					.perform(
							post("/api/v1/auth/login")
									.contentType(MediaType.APPLICATION_JSON)
									.content(requestBody))
					// @WebMvcTest에서 컨트롤러 핸들러 부재 → 404
					.andExpect(status().isNotFound());
		}
	}
}
