package com.econo.auth.api.presentation.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.auth.api.config.security.SecurityConfig;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.SelfRegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.SelfRegisterOAuthClientResult;
import com.econo.auth.client.exception.ClientLimitExceededException;
import com.econo.auth.client.exception.DuplicateClientNameException;
import com.econo.auth.client.exception.RedirectUriRequiredException;
import com.econo.common.auth.config.AuthAutoConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * ClientController 웹 레이어 테스트 — POST /api/v1/clients 셀프 등록
 *
 * <p>커버 범위:
 *
 * <ul>
 *   <li>인증된 회원(임의 역할) 등록 성공 → 201 + clientId + clientSecret
 *   <li>X-User-Passport 헤더 없음 → 401
 *   <li>memberId 파싱 불가 → 401
 *   <li>CLIENT_LIMIT_EXCEEDED(422) 에러 응답
 *   <li>REDIRECT_URI_REQUIRED(400) 에러 응답
 *   <li>DUPLICATE_CLIENT_NAME(409) 에러 응답
 *   <li>clientName 빈 문자열 → 400 VALIDATION_FAILED
 * </ul>
 */
@WebMvcTest(ClientController.class)
@Import({SecurityConfig.class, AuthAutoConfiguration.class})
class ClientControllerTest {

	@Autowired private MockMvc mockMvc;

	@MockBean private RegisterOAuthClientUseCase registerOAuthClientUseCase;

	/** 일반 회원(USER 역할) Passport — memberId=10 */
	private static final String USER_PASSPORT =
			Base64.getEncoder()
					.encodeToString(
							"{\"memberId\":10,\"roles\":[\"USER\"],\"issuedAt\":\"2026-01-01T00:00:00\",\"expiresAt\":\"2099-01-01T00:00:00\"}"
									.getBytes(StandardCharsets.UTF_8));

	/** ADMIN 역할 Passport — memberId=1 */
	private static final String ADMIN_PASSPORT =
			Base64.getEncoder()
					.encodeToString(
							"{\"memberId\":1,\"roles\":[\"ADMIN\"],\"issuedAt\":\"2026-01-01T00:00:00\",\"expiresAt\":\"2099-01-01T00:00:00\"}"
									.getBytes(StandardCharsets.UTF_8));

	// ──────────────────────────────────────────────────────────
	// 성공 케이스
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/clients — 셀프 등록 성공")
	class RegisterSuccessTest {

		@Test
		@DisplayName("인증된 일반 회원(USER)으로 등록 성공 → 201 + clientId + clientSecret 반환")
		void selfRegister_withAuthenticatedUser_returns201WithClientIdAndSecret() throws Exception {
			String expectedClientId = "client-uuid-self-001";
			String expectedSecret = "plain-secret-abc";
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willReturn(new SelfRegisterOAuthClientResult(expectedClientId, expectedSecret));

			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "내서비스앱",
																								"redirectUris": ["https://my-service.example.com/callback"]
																						}
																						"""))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").value(expectedClientId))
					.andExpect(jsonPath("$.clientSecret").value(expectedSecret));
		}

		@Test
		@DisplayName("ADMIN 역할도 셀프 등록 엔드포인트 사용 가능 → 201")
		void selfRegister_withAdminRole_returns201() throws Exception {
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willReturn(new SelfRegisterOAuthClientResult("client-admin-self", "admin-secret"));

			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", ADMIN_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "어드민셀프앱",
																								"redirectUris": ["https://admin.example.com/callback"]
																						}
																						"""))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").isNotEmpty())
					.andExpect(jsonPath("$.clientSecret").isNotEmpty());
		}

		@Test
		@DisplayName("응답 body에 clientSecret 필드가 반드시 존재함 (1회 노출)")
		void selfRegister_responseContainsClientSecret() throws Exception {
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willReturn(new SelfRegisterOAuthClientResult("some-client-id", "one-time-secret"));

			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "시크릿노출앱",
																								"redirectUris": ["https://secret.example.com/cb"]
																						}
																						"""))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientSecret").exists())
					.andExpect(jsonPath("$.clientSecret").isNotEmpty());
		}
	}

	// ──────────────────────────────────────────────────────────
	// 인증 실패 케이스
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/clients — 인증 실패")
	class AuthenticationFailureTest {

		@Test
		@DisplayName("X-User-Passport 헤더 없이 요청 시 401 반환")
		void selfRegister_withoutPassportHeader_returns401() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.content(
											"""
																						{
																								"clientName": "비인증앱",
																								"redirectUris": ["https://unauth.example.com/callback"]
																						}
																						"""))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("X-User-Passport 헤더가 빈 문자열이면 401 반환")
		void selfRegister_withBlankPassportHeader_returns401() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", "")
									.content(
											"""
																						{
																								"clientName": "빈헤더앱",
																								"redirectUris": ["https://blank.example.com/callback"]
																						}
																						"""))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("X-User-Passport Base64 디코딩 불가 → 400 반환")
		void selfRegister_withInvalidBase64Passport_returns400() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", "NOT_VALID_BASE64!!!")
									.content(
											"""
																						{
																								"clientName": "잘못된헤더앱",
																								"redirectUris": ["https://invalid.example.com/callback"]
																						}
																						"""))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("Passport에 memberId가 없으면 401 반환 (invalid Passport → UNAUTHORIZED)")
		void selfRegister_withNoMemberIdInPassport_returns401() throws Exception {
			// memberId 필드가 없는 Passport JSON — isValid()=false → invalid() → 401
			String passportWithoutMemberId =
					Base64.getEncoder()
							.encodeToString(
									"{\"roles\":[\"USER\"],\"issuedAt\":\"2026-01-01T00:00:00\"}"
											.getBytes(StandardCharsets.UTF_8));

			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", passportWithoutMemberId)
									.content(
											"""
																						{
																								"clientName": "멤버아이디없는앱",
																								"redirectUris": ["https://nomember.example.com/callback"]
																						}
																						"""))
					.andExpect(status().isUnauthorized());
		}
	}

	// ──────────────────────────────────────────────────────────
	// 비즈니스 규칙 위반 케이스
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/clients — 비즈니스 규칙 위반")
	class BusinessRuleViolationTest {

		@Test
		@DisplayName("5개 한도 초과 시 422 CLIENT_LIMIT_EXCEEDED 반환")
		void selfRegister_whenLimitExceeded_returns422WithClientLimitExceededCode() throws Exception {
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willThrow(new ClientLimitExceededException());

			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "초과앱",
																								"redirectUris": ["https://over.example.com/callback"]
																						}
																						"""))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.errorCode").value("CLIENT_LIMIT_EXCEEDED"));
		}

		@Test
		@DisplayName("redirectUris 빈 Set 시 400 REDIRECT_URI_REQUIRED 반환")
		void selfRegister_withoutRedirectUris_returns400WithRedirectUriRequired() throws Exception {
			// @NotNull로 null은 컨트롤러에서 차단(VALIDATION_FAILED),
			// 빈 Set은 서비스에서 RedirectUriRequiredException → REDIRECT_URI_REQUIRED
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willThrow(new RedirectUriRequiredException());

			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "리다이렉트없는앱",
																								"redirectUris": []
																						}
																						"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("REDIRECT_URI_REQUIRED"));
		}

		@Test
		@DisplayName("clientName 중복 시 409 DUPLICATE_CLIENT_NAME 반환")
		void selfRegister_withDuplicateClientName_returns409() throws Exception {
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willThrow(new DuplicateClientNameException("중복앱이름"));

			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "중복앱이름",
																								"redirectUris": ["https://dup.example.com/callback"]
																						}
																						"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.errorCode").value("DUPLICATE_CLIENT_NAME"));
		}

		@Test
		@DisplayName("clientName이 빈 문자열이면 400 VALIDATION_FAILED 반환")
		void selfRegister_withBlankClientName_returns400ValidationFailed() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "",
																								"redirectUris": ["https://blank.example.com/callback"]
																						}
																						"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}
	}
}
