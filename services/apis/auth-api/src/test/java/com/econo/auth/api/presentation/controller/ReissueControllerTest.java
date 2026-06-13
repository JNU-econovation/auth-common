package com.econo.auth.api.presentation.controller;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.auth.api.config.security.SecurityConfig;
import com.econo.auth.api.presentation.util.TokenCookieManager;
import com.econo.auth.login.application.usecase.LoginTokenUseCase;
import com.econo.auth.login.exception.InvalidTokenException;
import com.econo.auth.login.exception.WrongTokenTypeException;
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
 * ReissueController 웹 레이어 테스트 — POST /api/v1/auth/reissue
 *
 * <p>커버 범위:
 *
 * <ul>
 *   <li>RT 누락 → 401 REFRESH_TOKEN_MISSING (에러 응답에 timestamp 포함 검증)
 *   <li>RT 무효 → 401 REFRESH_TOKEN_INVALID (에러 응답에 timestamp 포함 검증)
 *   <li>AT로 재발급 시도 → 401 REFRESH_TOKEN_INVALID (에러 응답에 timestamp 포함 검증)
 *   <li>APP 클라이언트 재발급 성공 → 200 + body
 *   <li>WEB 로그아웃 → 200
 * </ul>
 *
 * <p><b>Red 이유</b>: 현재 {@code ReissueController}는 내부 {@code record ErrorResponse(String errorCode,
 * String message)}(2필드)를 사용하므로 에러 응답 JSON에 {@code timestamp} 필드가 존재하지 않는다. 이번 작업에서 공용 {@code
 * presentation/dto/ErrorResponse}(3필드 — {@code errorCode, message, timestamp})로 교체되면 {@code
 * $.timestamp} 검증이 통과한다.
 */
@WebMvcTest(ReissueController.class)
@Import(SecurityConfig.class)
class ReissueControllerTest {

	@Autowired private MockMvc mockMvc;

	@MockBean private LoginTokenUseCase loginTokenUseCase;
	@MockBean private TokenCookieManager cookieManager;

	// ──────────────────────────────────────────────────────────
	// 에러 응답 timestamp 포함 검증 (동작 변경 — Red 핵심)
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/auth/reissue 에러 응답 — timestamp 포함 (공용 ErrorResponse 통합)")
	class ErrorResponseTimestampTest {

		@Test
		@DisplayName("APP 클라이언트에서 RT 없이 요청 시 401 + errorCode + timestamp 포함")
		void reissue_missingRefreshToken_returns401WithTimestamp() throws Exception {
			// APP 클라이언트에서 body가 없거나 refreshToken이 null인 경우
			mockMvc
					.perform(
							post("/api/v1/auth/reissue")
									.header("Client-Type", "APP")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{}"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("REFRESH_TOKEN_MISSING"))
					.andExpect(jsonPath("$.timestamp").exists()); // ← Red: 현재 2필드 ErrorResponse에는 없음
		}

		@Test
		@DisplayName("APP 클라이언트에서 유효하지 않은 RT 요청 시 401 + errorCode + timestamp 포함")
		void reissue_invalidRefreshToken_returns401WithTimestamp() throws Exception {
			given(loginTokenUseCase.verifyRefreshTokenAndGetMemberId("invalid-rt"))
					.willThrow(new InvalidTokenException("invalid"));

			mockMvc
					.perform(
							post("/api/v1/auth/reissue")
									.header("Client-Type", "APP")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"refreshToken\":\"invalid-rt\"}"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("REFRESH_TOKEN_INVALID"))
					.andExpect(jsonPath("$.timestamp").exists()); // ← Red: 현재 2필드 ErrorResponse에는 없음
		}

		@Test
		@DisplayName("AT로 재발급 시도(WrongTokenType) 시 401 + errorCode + timestamp 포함")
		void reissue_wrongTokenType_returns401WithTimestamp() throws Exception {
			given(loginTokenUseCase.verifyRefreshTokenAndGetMemberId("access-token-used-as-rt"))
					.willThrow(new WrongTokenTypeException("expected RT"));

			mockMvc
					.perform(
							post("/api/v1/auth/reissue")
									.header("Client-Type", "APP")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"refreshToken\":\"access-token-used-as-rt\"}"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("REFRESH_TOKEN_INVALID"))
					.andExpect(jsonPath("$.timestamp").exists()); // ← Red: 현재 2필드 ErrorResponse에는 없음
		}

		@Test
		@DisplayName("에러 응답 body는 errorCode, message, timestamp 세 필드를 모두 포함해야 한다")
		void reissue_errorResponse_containsAllThreeFields() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/auth/reissue")
									.header("Client-Type", "APP")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{}"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").exists())
					.andExpect(jsonPath("$.message").exists())
					.andExpect(jsonPath("$.timestamp").exists()); // ← Red: 공용 ErrorResponse 미구현
		}

		@Test
		@DisplayName("WEB 클라이언트에서 RT 쿠키 없이 요청 시 401 + timestamp 포함")
		void reissue_webClientNoCookie_returns401WithTimestamp() throws Exception {
			given(cookieManager.extractRtFromCookie(any())).willReturn(java.util.Optional.empty());

			mockMvc
					.perform(
							post("/api/v1/auth/reissue")
									.contentType(MediaType.APPLICATION_JSON)) // Client-Type 헤더 없음 → 기본값 WEB
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("REFRESH_TOKEN_MISSING"))
					.andExpect(jsonPath("$.timestamp").exists()); // ← Red: 공용 ErrorResponse 미구현
		}
	}

	// ──────────────────────────────────────────────────────────
	// 기존 동작 유지 검증 (Green 유지 목적 — 동작 불변)
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/auth/reissue — 성공 케이스 (기존 동작 유지)")
	class ReissueSuccessTest {

		@Test
		@DisplayName("APP 클라이언트에서 유효한 RT로 재발급 성공 → 200 + accessToken + refreshToken")
		void reissue_validRefreshToken_app_returns200WithTokens() throws Exception {
			given(loginTokenUseCase.verifyRefreshTokenAndGetMemberId("valid-rt")).willReturn(42L);
			given(loginTokenUseCase.reissue(42L))
					.willReturn(new LoginTokenUseCase.TokenPair("new-at", 9999999999L, "new-rt"));

			mockMvc
					.perform(
							post("/api/v1/auth/reissue")
									.header("Client-Type", "APP")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"refreshToken\":\"valid-rt\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.accessToken").value("new-at"))
					.andExpect(jsonPath("$.refreshToken").value("new-rt"))
					.andExpect(jsonPath("$.accessExpiredTime").value(9999999999L));
		}
	}

	// ──────────────────────────────────────────────────────────
	// POST /api/v1/auth/logout — 기존 동작 유지
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/auth/logout — 로그아웃 (기존 동작 유지)")
	class LogoutTest {

		@Test
		@DisplayName("WEB 클라이언트 로그아웃 → 200 (쿠키 삭제)")
		void logout_webClient_returns200() throws Exception {
			mockMvc
					.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON))
					.andExpect(status().isOk());

			then(cookieManager).should().deleteAtCookie(any());
			then(cookieManager).should().deleteRtCookie(any());
		}

		@Test
		@DisplayName("APP 클라이언트 로그아웃 → 200 (쿠키 삭제 없음)")
		void logout_appClient_returns200NoCookieDeletion() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/auth/logout")
									.header("Client-Type", "APP")
									.contentType(MediaType.APPLICATION_JSON))
					.andExpect(status().isOk());

			then(cookieManager).should(never()).deleteAtCookie(any());
			then(cookieManager).should(never()).deleteRtCookie(any());
		}
	}
}
