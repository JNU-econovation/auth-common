package com.econo.auth.api.presentation.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.econo.auth.api.config.security.SecurityConfig;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase.DeleteMyClientCommand;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase.MyClientResult;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase.UpdateMyClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.SelfRegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.SelfRegisterOAuthClientResult;
import com.econo.auth.client.exception.ClientLimitExceededException;
import com.econo.auth.client.exception.DuplicateClientNameException;
import com.econo.auth.client.exception.InvalidClientException;
import com.econo.auth.client.exception.RedirectUriRequiredException;
import com.econo.auth.client.exception.RouteNamespaceChangeException;
import com.econo.auth.client.exception.RouteNamespaceInvalidException;
import com.econo.auth.client.exception.RouteNamespaceTakenException;
import com.econo.auth.client.exception.RoutePathConflictException;
import com.econo.auth.client.exception.RouteProtectedException;
import com.econo.auth.client.exception.RouteUpstreamInvalidException;
import com.econo.common.auth.config.AuthAutoConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
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
	@MockBean private ManageOwnClientUseCase manageOwnClientUseCase;

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
					.willReturn(
							new SelfRegisterOAuthClientResult(
									expectedClientId, expectedSecret, null, null, null, null));

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
					.willReturn(
							new SelfRegisterOAuthClientResult(
									"client-admin-self", "admin-secret", null, null, null, null));

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
					.willReturn(
							new SelfRegisterOAuthClientResult(
									"some-client-id", "one-time-secret", null, null, null, null));

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

	// ──────────────────────────────────────────────────────────
	// 라우트 흡수 등록 케이스
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/clients — 라우트 흡수 등록")
	class RegisterRouteAbsorptionTest {

		@Test
		@DisplayName("라우트 필드 둘 다 있는 요청 → 201 + 라우트 필드 포함")
		void selfRegister_withBothRouteFields_returns201WithRouteFields() throws Exception {
			// given
			String routeId = "route-uuid-001";
			String pathPrefix = "/api/my/**";
			String upstreamUrl = "https://my.example.com";
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willReturn(
							new SelfRegisterOAuthClientResult(
									"client-uuid-route",
									"plain-secret-route",
									routeId,
									pathPrefix,
									upstreamUrl,
									true));

			// when / then
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "라우트포함앱",
																								"redirectUris": ["https://my.example.com/callback"],
																								"pathPrefix": "/api/my/**",
																								"upstreamUrl": "https://my.example.com"
																						}
																						"""))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.routeId").value(routeId))
					.andExpect(jsonPath("$.pathPrefix").value(pathPrefix))
					.andExpect(jsonPath("$.upstreamUrl").value(upstreamUrl))
					.andExpect(jsonPath("$.enabled").value(true));
		}

		@Test
		@DisplayName("라우트 필드 없는 요청 → 201 + 라우트 필드 null")
		void selfRegister_withoutRouteFields_returns201WithNullRouteFields() throws Exception {
			// given
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willReturn(
							new SelfRegisterOAuthClientResult(
									"client-uuid-noroute", "plain-secret-noroute", null, null, null, null));

			// when / then
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "라우트없는앱",
																								"redirectUris": ["https://noroute.example.com/callback"]
																						}
																						"""))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.routeId").value(nullValue()))
					.andExpect(jsonPath("$.pathPrefix").value(nullValue()))
					.andExpect(jsonPath("$.upstreamUrl").value(nullValue()))
					.andExpect(jsonPath("$.enabled").value(nullValue()));
		}

		@Test
		@DisplayName("pathPrefix만 있고 upstreamUrl 없음 → 400 VALIDATION_FAILED")
		void selfRegister_withOnlyPathPrefix_returns400ValidationFailed() throws Exception {
			// given — @AssertTrue isRouteFieldsConsistent() 실패 → MethodArgumentNotValidException
			// (서비스 레이어 미진입, stub 불필요)

			// when / then
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "한필드앱",
																								"redirectUris": ["https://onefield.example.com/callback"],
																								"pathPrefix": "/api/my/**"
																						}
																						"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("upstreamUrl만 있고 pathPrefix 없음 → 400 VALIDATION_FAILED")
		void selfRegister_withOnlyUpstreamUrl_returns400ValidationFailed() throws Exception {
			// given — @AssertTrue isRouteFieldsConsistent() 실패 → MethodArgumentNotValidException

			// when / then
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "한필드앱2",
																								"redirectUris": ["https://onefield2.example.com/callback"],
																								"upstreamUrl": "https://my.example.com"
																						}
																						"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("네임스페이스 포맷 위반 → 400 ROUTE_NAMESPACE_INVALID")
		void selfRegister_whenNamespaceInvalid_returns400() throws Exception {
			// given
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willThrow(new RouteNamespaceInvalidException("/not-api/x"));

			// when / then
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "네임스페이스위반앱",
																								"redirectUris": ["https://ns.example.com/callback"],
																								"pathPrefix": "/not-api/x/**",
																								"upstreamUrl": "https://upstream.example.com"
																						}
																						"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_NAMESPACE_INVALID"));
		}

		@Test
		@DisplayName("네임스페이스 선점 → 403 ROUTE_NAMESPACE_TAKEN")
		void selfRegister_whenNamespaceTaken_returns403() throws Exception {
			// given
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willThrow(new RouteNamespaceTakenException("eeos"));

			// when / then
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "선점충돌앱",
																								"redirectUris": ["https://taken.example.com/callback"],
																								"pathPrefix": "/api/eeos/**",
																								"upstreamUrl": "https://eeos.example.com"
																						}
																						"""))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_NAMESPACE_TAKEN"));
		}

		@Test
		@DisplayName("pathPrefix 중복 → 409 ROUTE_PATH_CONFLICT")
		void selfRegister_whenPathConflict_returns409() throws Exception {
			// given
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willThrow(new RoutePathConflictException("/api/my/**"));

			// when / then
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "경로중복앱",
																								"redirectUris": ["https://conflict.example.com/callback"],
																								"pathPrefix": "/api/my/**",
																								"upstreamUrl": "https://my.example.com"
																						}
																						"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_PATH_CONFLICT"));
		}

		@Test
		@DisplayName("SSRF URL → 400 ROUTE_UPSTREAM_INVALID")
		void selfRegister_whenUpstreamInvalid_returns400() throws Exception {
			// given
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willThrow(new RouteUpstreamInvalidException("private IP"));

			// when / then
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "SSRF앱",
																								"redirectUris": ["https://ssrf.example.com/callback"],
																								"pathPrefix": "/api/ssrf/**",
																								"upstreamUrl": "http://192.168.1.1/admin"
																						}
																						"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_UPSTREAM_INVALID"));
		}

		@Test
		@DisplayName("보호 경로 충돌 → 403 ROUTE_PROTECTED")
		void selfRegister_whenRouteProtected_returns403() throws Exception {
			// given
			given(registerOAuthClientUseCase.selfRegister(any(SelfRegisterOAuthClientCommand.class)))
					.willThrow(new RouteProtectedException("/api/v1/auth/**"));

			// when / then
			mockMvc
					.perform(
							post("/api/v1/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "보호경로앱",
																								"redirectUris": ["https://protected.example.com/callback"],
																								"pathPrefix": "/api/v1/auth/**",
																								"upstreamUrl": "https://protected.example.com"
																						}
																						"""))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_PROTECTED"));
		}
	}

	// ──────────────────────────────────────────────────────────
	// GET /api/v1/clients — 내 클라이언트 목록
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("GET /api/v1/clients — 내 클라이언트 목록 조회")
	class ListMyClientsTest {

		@Test
		@DisplayName("인증된 회원으로 목록 조회 성공 → 200 + clients 배열 반환")
		void listMyClients_withAuthenticatedUser_returns200WithClientsList() throws Exception {
			// given
			MyClientResult result1 =
					new MyClientResult(
							"client-uuid-1",
							"EEOS 웹앱",
							Set.of("https://app.econovation.kr/callback"),
							"route-uuid-1",
							"/api/eeos",
							"http://eeos-service:8080",
							true);
			MyClientResult result2 =
					new MyClientResult(
							"client-uuid-2", "EEOS 앱", Set.of("eeos://callback"), null, null, null, null);

			given(manageOwnClientUseCase.listMyClients(10L)).willReturn(List.of(result1, result2));

			// when / then
			mockMvc
					.perform(get("/api/v1/clients").header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.clients").isArray())
					.andExpect(jsonPath("$.clients.length()").value(2))
					.andExpect(jsonPath("$.clients[0].clientId").value("client-uuid-1"))
					.andExpect(jsonPath("$.clients[0].clientName").value("EEOS 웹앱"))
					.andExpect(jsonPath("$.clients[0].route.routeId").value("route-uuid-1"));
		}

		@Test
		@DisplayName("클라이언트 없으면 200 + clients 빈 배열 반환")
		void listMyClients_withNoClients_returns200WithEmptyList() throws Exception {
			// given
			given(manageOwnClientUseCase.listMyClients(10L)).willReturn(List.of());

			// when / then
			mockMvc
					.perform(get("/api/v1/clients").header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.clients").isArray())
					.andExpect(jsonPath("$.clients.length()").value(0));
		}

		@Test
		@DisplayName("응답에 clientSecret 필드가 없음 (절대 포함 금지)")
		void listMyClients_responseDoesNotContainClientSecret() throws Exception {
			// given
			MyClientResult result =
					new MyClientResult(
							"client-uuid-nosecret",
							"시크릿없는앱",
							Set.of("https://nosecret.example.com/cb"),
							null,
							null,
							null,
							null);
			given(manageOwnClientUseCase.listMyClients(10L)).willReturn(List.of(result));

			// when / then
			mockMvc
					.perform(get("/api/v1/clients").header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.clients[0].clientSecret").doesNotExist());
		}

		@Test
		@DisplayName("X-User-Passport 헤더 없이 목록 요청 시 401 반환")
		void listMyClients_withoutPassportHeader_returns401() throws Exception {
			// when / then
			mockMvc.perform(get("/api/v1/clients")).andExpect(status().isUnauthorized());
		}
	}

	// ──────────────────────────────────────────────────────────
	// GET /api/v1/clients/{clientId} — 단건 상세 조회
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("GET /api/v1/clients/{clientId} — 단건 상세 조회")
	class GetMyClientTest {

		@Test
		@DisplayName("소유한 클라이언트 상세 조회 성공 → 200 + clientId + route 포함")
		void getMyClient_withOwnedClient_returns200WithDetail() throws Exception {
			// given
			String clientId = "client-uuid-detail";
			MyClientResult result =
					new MyClientResult(
							clientId,
							"EEOS 웹앱",
							Set.of("https://app.econovation.kr/callback"),
							"route-uuid-1",
							"/api/eeos",
							"http://eeos-service:8080",
							true);
			given(manageOwnClientUseCase.getMyClient(clientId, 10L)).willReturn(result);

			// when / then
			mockMvc
					.perform(
							get("/api/v1/clients/{clientId}", clientId).header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.clientId").value(clientId))
					.andExpect(jsonPath("$.clientName").value("EEOS 웹앱"))
					.andExpect(jsonPath("$.route.pathPrefix").value("/api/eeos"))
					.andExpect(jsonPath("$.route.enabled").value(true));
		}

		@Test
		@DisplayName("타인 소유 클라이언트 조회 → 404 CLIENT_NOT_FOUND (존재 은닉)")
		void getMyClient_withOtherOwnersClient_returns404ClientNotFound() throws Exception {
			// given
			String otherClientId = "client-uuid-other";
			given(manageOwnClientUseCase.getMyClient(otherClientId, 10L))
					.willThrow(new InvalidClientException());

			// when / then
			mockMvc
					.perform(
							get("/api/v1/clients/{clientId}", otherClientId)
									.header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.errorCode").value("CLIENT_NOT_FOUND"));
		}

		@Test
		@DisplayName("존재하지 않는 clientId 조회 → 404 CLIENT_NOT_FOUND")
		void getMyClient_withNonExistentClientId_returns404() throws Exception {
			// given
			String nonExistentId = "non-existent-client";
			given(manageOwnClientUseCase.getMyClient(nonExistentId, 10L))
					.willThrow(new InvalidClientException());

			// when / then
			mockMvc
					.perform(
							get("/api/v1/clients/{clientId}", nonExistentId)
									.header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.errorCode").value("CLIENT_NOT_FOUND"));
		}

		@Test
		@DisplayName("상세 응답에 clientSecret 필드가 없음 (절대 포함 금지)")
		void getMyClient_responseDoesNotContainClientSecret() throws Exception {
			// given
			String clientId = "client-uuid-nosecret";
			MyClientResult result =
					new MyClientResult(
							clientId,
							"시크릿없는앱",
							Set.of("https://nosecret.example.com/cb"),
							null,
							null,
							null,
							null);
			given(manageOwnClientUseCase.getMyClient(clientId, 10L)).willReturn(result);

			// when / then
			mockMvc
					.perform(
							get("/api/v1/clients/{clientId}", clientId).header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.clientSecret").doesNotExist());
		}

		@Test
		@DisplayName("X-User-Passport 헤더 없이 상세 조회 시 401 반환")
		void getMyClient_withoutPassportHeader_returns401() throws Exception {
			// when / then
			mockMvc
					.perform(get("/api/v1/clients/{clientId}", "any-client-id"))
					.andExpect(status().isUnauthorized());
		}
	}

	// ──────────────────────────────────────────────────────────
	// PUT /api/v1/clients/{clientId} — 수정
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("PUT /api/v1/clients/{clientId} — 클라이언트 수정")
	class UpdateMyClientTest {

		@Test
		@DisplayName("정상 수정 요청 → 200 + 수정된 클라이언트 정보 반환")
		void updateMyClient_withValidRequest_returns200WithUpdatedClient() throws Exception {
			// given
			String clientId = "client-uuid-update";
			MyClientResult updatedResult =
					new MyClientResult(
							clientId,
							"EEOS 웹앱 v2",
							Set.of("https://app.econovation.kr/callback", "https://dev.econovation.kr/callback"),
							"route-uuid-1",
							"/api/eeos",
							"http://eeos-service-v2:8080",
							true);
			given(manageOwnClientUseCase.updateMyClient(any(UpdateMyClientCommand.class)))
					.willReturn(updatedResult);

			// when / then
			mockMvc
					.perform(
							put("/api/v1/clients/{clientId}", clientId)
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "EEOS 웹앱 v2",
																								"redirectUris": ["https://app.econovation.kr/callback", "https://dev.econovation.kr/callback"],
																								"pathPrefix": "/api/eeos",
																								"upstreamUrl": "http://eeos-service-v2:8080"
																						}
																						"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.clientId").value(clientId))
					.andExpect(jsonPath("$.clientName").value("EEOS 웹앱 v2"))
					.andExpect(jsonPath("$.route.upstreamUrl").value("http://eeos-service-v2:8080"));
		}

		@Test
		@DisplayName("PUT 수정 응답에 clientSecret 필드가 없음 (절대 포함 금지)")
		void updateMyClient_responseDoesNotContainClientSecret() throws Exception {
			// given
			String clientId = "client-uuid-put-nosecret";
			MyClientResult result =
					new MyClientResult(
							clientId,
							"시크릿없는앱",
							Set.of("https://nosecret.example.com/cb"),
							null,
							null,
							null,
							null);
			given(manageOwnClientUseCase.updateMyClient(any(UpdateMyClientCommand.class)))
					.willReturn(result);

			// when / then
			mockMvc
					.perform(
							put("/api/v1/clients/{clientId}", clientId)
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "시크릿없는앱",
																								"redirectUris": ["https://nosecret.example.com/cb"]
																						}
																						"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.clientSecret").doesNotExist());
		}

		@Test
		@DisplayName("타인 소유 클라이언트 수정 시도 → 404 CLIENT_NOT_FOUND")
		void updateMyClient_withOtherOwnersClient_returns404ClientNotFound() throws Exception {
			// given
			String otherClientId = "client-uuid-other";
			given(manageOwnClientUseCase.updateMyClient(any(UpdateMyClientCommand.class)))
					.willThrow(new InvalidClientException());

			// when / then
			mockMvc
					.perform(
							put("/api/v1/clients/{clientId}", otherClientId)
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "남의앱",
																								"redirectUris": ["https://other.example.com/cb"]
																						}
																						"""))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.errorCode").value("CLIENT_NOT_FOUND"));
		}

		@Test
		@DisplayName("네임스페이스 변경 시도 → 400 ROUTE_NAMESPACE_CHANGE_DENIED")
		void updateMyClient_whenNamespaceChanged_returns400RouteNamespaceChangeDenied()
				throws Exception {
			// given
			String clientId = "client-uuid-namespace";
			given(manageOwnClientUseCase.updateMyClient(any(UpdateMyClientCommand.class)))
					.willThrow(RouteNamespaceChangeException.denied("eeos", "eeos-v2"));

			// when / then
			mockMvc
					.perform(
							put("/api/v1/clients/{clientId}", clientId)
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "EEOS 웹앱",
																								"redirectUris": ["https://app.econovation.kr/callback"],
																								"pathPrefix": "/api/eeos-v2",
																								"upstreamUrl": "http://eeos-v2:8080"
																						}
																						"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_NAMESPACE_CHANGE_DENIED"));
		}

		@Test
		@DisplayName("clientName 빈 문자열 → 400 VALIDATION_FAILED")
		void updateMyClient_withBlankClientName_returns400ValidationFailed() throws Exception {
			// when / then
			mockMvc
					.perform(
							put("/api/v1/clients/{clientId}", "client-uuid-blank")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "",
																								"redirectUris": ["https://blank.example.com/cb"]
																						}
																						"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("pathPrefix만 있고 upstreamUrl 없음 → 400 VALIDATION_FAILED")
		void updateMyClient_withOnlyPathPrefix_returns400ValidationFailed() throws Exception {
			// when / then
			mockMvc
					.perform(
							put("/api/v1/clients/{clientId}", "client-uuid-halfroute")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "반쪽라우트앱",
																								"redirectUris": ["https://half.example.com/cb"],
																								"pathPrefix": "/api/halfroute"
																						}
																						"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}

		@Test
		@DisplayName("SSRF upstreamUrl → 400 ROUTE_UPSTREAM_INVALID")
		void updateMyClient_whenUpstreamInvalid_returns400RouteUpstreamInvalid() throws Exception {
			// given
			String clientId = "client-uuid-ssrf";
			given(manageOwnClientUseCase.updateMyClient(any(UpdateMyClientCommand.class)))
					.willThrow(new RouteUpstreamInvalidException("private IP"));

			// when / then
			mockMvc
					.perform(
							put("/api/v1/clients/{clientId}", clientId)
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "SSRF앱",
																								"redirectUris": ["https://ssrf.example.com/cb"],
																								"pathPrefix": "/api/ssrf",
																								"upstreamUrl": "http://192.168.1.1/admin"
																						}
																						"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_UPSTREAM_INVALID"));
		}

		@Test
		@DisplayName("보호 경로 pathPrefix → 403 ROUTE_PROTECTED")
		void updateMyClient_whenRouteProtected_returns403RouteProtected() throws Exception {
			// given
			String clientId = "client-uuid-protected";
			given(manageOwnClientUseCase.updateMyClient(any(UpdateMyClientCommand.class)))
					.willThrow(new RouteProtectedException("/api/v1/auth/**"));

			// when / then
			mockMvc
					.perform(
							put("/api/v1/clients/{clientId}", clientId)
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-User-Passport", USER_PASSPORT)
									.content(
											"""
																						{
																								"clientName": "보호경로앱",
																								"redirectUris": ["https://protected.example.com/cb"],
																								"pathPrefix": "/api/v1/auth",
																								"upstreamUrl": "https://protected.example.com"
																						}
																						"""))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.errorCode").value("ROUTE_PROTECTED"));
		}

		@Test
		@DisplayName("X-User-Passport 헤더 없이 수정 요청 시 401 반환")
		void updateMyClient_withoutPassportHeader_returns401() throws Exception {
			// when / then
			mockMvc
					.perform(
							put("/api/v1/clients/{clientId}", "any-client-id")
									.contentType(MediaType.APPLICATION_JSON)
									.content(
											"""
																						{
																								"clientName": "비인증앱",
																								"redirectUris": ["https://unauth.example.com/cb"]
																						}
																						"""))
					.andExpect(status().isUnauthorized());
		}
	}

	// ──────────────────────────────────────────────────────────
	// DELETE /api/v1/clients/{clientId} — 하드 삭제
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("DELETE /api/v1/clients/{clientId} — 클라이언트 하드 삭제")
	class DeleteMyClientTest {

		@Test
		@DisplayName("소유한 클라이언트 삭제 성공 → 204 No Content")
		void deleteMyClient_withOwnedClient_returns204() throws Exception {
			// given
			String clientId = "client-uuid-delete";
			willDoNothing()
					.given(manageOwnClientUseCase)
					.deleteMyClient(any(DeleteMyClientCommand.class));

			// when / then
			mockMvc
					.perform(
							delete("/api/v1/clients/{clientId}", clientId)
									.header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("타인 소유 클라이언트 삭제 시도 → 404 CLIENT_NOT_FOUND (존재 은닉)")
		void deleteMyClient_withOtherOwnersClient_returns404ClientNotFound() throws Exception {
			// given
			String otherClientId = "client-uuid-other-delete";
			willThrow(new InvalidClientException())
					.given(manageOwnClientUseCase)
					.deleteMyClient(any(DeleteMyClientCommand.class));

			// when / then
			mockMvc
					.perform(
							delete("/api/v1/clients/{clientId}", otherClientId)
									.header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.errorCode").value("CLIENT_NOT_FOUND"));
		}

		@Test
		@DisplayName("존재하지 않는 clientId 삭제 → 404 CLIENT_NOT_FOUND (존재 은닉)")
		void deleteMyClient_withNonExistentClientId_returns404() throws Exception {
			// given
			String nonExistentId = "non-existent-for-delete";
			willThrow(new InvalidClientException())
					.given(manageOwnClientUseCase)
					.deleteMyClient(any(DeleteMyClientCommand.class));

			// when / then
			mockMvc
					.perform(
							delete("/api/v1/clients/{clientId}", nonExistentId)
									.header("X-User-Passport", USER_PASSPORT))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.errorCode").value("CLIENT_NOT_FOUND"));
		}

		@Test
		@DisplayName("X-User-Passport 헤더 없이 삭제 요청 시 401 반환")
		void deleteMyClient_withoutPassportHeader_returns401() throws Exception {
			// when / then
			mockMvc
					.perform(delete("/api/v1/clients/{clientId}", "any-client-id"))
					.andExpect(status().isUnauthorized());
		}
	}
}
