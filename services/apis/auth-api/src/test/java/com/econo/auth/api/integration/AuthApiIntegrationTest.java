package com.econo.auth.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * auth-api 통합 테스트 — 실제 PostgreSQL 컨테이너 기반 전체 플로우 검증
 *
 * <p>커버 범위:
 *
 * <ul>
 *   <li>회원가입 (POST /api/v1/auth/signup)
 *   <li>로그인 WEB/APP 분기 (POST /api/v1/auth/login)
 *   <li>토큰 재발급 WEB/APP (POST /api/v1/auth/reissue)
 *   <li>로그아웃 (POST /api/v1/auth/logout)
 *   <li>JWKS 공개키 (GET /oauth2/jwks)
 *   <li>OAuth 클라이언트 등록/관리 (POST /api/v1/admin/clients)
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
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
		registry.add("RSA_PRIVATE_KEY", () -> TEST_PRIVATE_KEY_PEM);
		registry.add("RSA_PUBLIC_KEY", () -> TEST_PUBLIC_KEY_PEM);
		registry.add("AUTH_ISSUER_URI", () -> "http://localhost:8081");
		registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
		registry.add("AUTH_INTERNAL_API_KEY", () -> "test-internal-key");
		registry.add("COOKIE_SECURE", () -> "false");
	}

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;

	// ──────────────────────────────────────────────────────────
	// 회원가입
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/auth/signup")
	class SignupTest {

		@Test
		@DisplayName("정상 가입 시 201 반환")
		void signup_success() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(
											"""
											{"name":"홍길동","loginId":"honggildong","password":"Econo1234!",
											"generation":30,"status":"AM"}
											"""))
					.andExpect(status().isCreated());
		}

		@Test
		@DisplayName("loginId 중복 시 409 반환")
		void signup_duplicate_loginId() throws Exception {
			String body =
					"""
					{"name":"홍길동","loginId":"duplicate01","password":"Econo1234!","generation":30,"status":"AM"}
					""";
			mockMvc.perform(
					post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body));
			mockMvc
					.perform(
							post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.errorCode").value("MEMBER_ALREADY_EXISTS"));
		}

		@Test
		@DisplayName("비밀번호 정책 위반 시 400 반환")
		void signup_invalid_password() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/auth/signup")
									.contentType(MediaType.APPLICATION_JSON)
									.content(
											"""
											{"name":"홍길동","loginId":"user01","password":"weakpassword",
											"generation":30,"status":"AM"}
											"""))
					.andExpect(status().isBadRequest());
		}
	}

	// ──────────────────────────────────────────────────────────
	// WEB 로그인
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/auth/login — WEB (Client-Type: WEB)")
	class WebLoginTest {

		@Test
		@DisplayName("로그인 성공 시 at + rt 쿠키 발급, body엔 accessExpiredTime만")
		void web_login_issues_cookies_not_token_in_body() throws Exception {
			signup("webuser01");

			MvcResult result =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.header("Client-Type", "WEB")
											.content(
													"""
													{"loginId":"webuser01","password":"Econo1234!"}
													"""))
							.andExpect(status().isOk())
							.andReturn();

			MockHttpServletResponse response = result.getResponse();

			// 쿠키 검증
			String atCookie = getCookieValue(response, "at");
			String rtCookie = getCookieValue(response, "rt");
			assertThat(atCookie).isNotBlank();
			assertThat(rtCookie).isNotBlank();

			// body: accessToken 없음, accessExpiredTime만
			String body = response.getContentAsString();
			assertThat(body).doesNotContain("accessToken");
			assertThat(body).contains("accessExpiredTime");
		}

		@Test
		@DisplayName("at 쿠키는 HttpOnly, SameSite=None")
		void web_login_cookie_is_httponly() throws Exception {
			signup("webuser02");

			MvcResult result =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.header("Client-Type", "WEB")
											.content(
													"""
											{"loginId":"webuser02","password":"Econo1234!"}
											"""))
							.andReturn();

			String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
			assertThat(setCookieHeader).containsIgnoringCase("HttpOnly");
			assertThat(setCookieHeader).containsIgnoringCase("SameSite=None");
		}

		@Test
		@DisplayName("잘못된 비밀번호 → 401 INVALID_CREDENTIALS")
		void web_login_wrong_password() throws Exception {
			signup("webuser03");
			mockMvc
					.perform(
							post("/api/v1/auth/login")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Client-Type", "WEB")
									.content(
											"""
									{"loginId":"webuser03","password":"WrongPass1!"}
									"""))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
		}

		@Test
		@DisplayName("존재하지 않는 loginId → 401")
		void web_login_unknown_user() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/auth/login")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Client-Type", "WEB")
									.content(
											"""
									{"loginId":"unknown_nobody","password":"Econo1234!"}
									"""))
					.andExpect(status().isUnauthorized());
		}
	}

	// ──────────────────────────────────────────────────────────
	// APP 로그인
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/auth/login — APP (Client-Type: APP)")
	class AppLoginTest {

		@Test
		@DisplayName("로그인 성공 시 AT + RT 모두 body에 반환, 쿠키 없음")
		void app_login_returns_tokens_in_body() throws Exception {
			signup("appuser01");

			MvcResult result =
					mockMvc
							.perform(
									post("/api/v1/auth/login")
											.contentType(MediaType.APPLICATION_JSON)
											.header("Client-Type", "APP")
											.content(
													"""
											{"loginId":"appuser01","password":"Econo1234!"}
											"""))
							.andExpect(status().isOk())
							.andExpect(jsonPath("$.accessToken").isNotEmpty())
							.andExpect(jsonPath("$.refreshToken").isNotEmpty())
							.andExpect(jsonPath("$.accessExpiredTime").isNumber())
							.andReturn();

			// 쿠키 없음
			assertThat(result.getResponse().getCookie("at")).isNull();
			assertThat(result.getResponse().getCookie("rt")).isNull();
		}
	}

	// ──────────────────────────────────────────────────────────
	// 재발급
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/auth/reissue")
	class ReissueTest {

		@Test
		@DisplayName("WEB: rt 쿠키로 재발급 → 새 at + rt 쿠키 교체")
		void web_reissue_with_rt_cookie() throws Exception {
			signup("reissue_web01");
			MvcResult loginResult = login("reissue_web01", "WEB");
			String rtCookie = getCookieValue(loginResult.getResponse(), "rt");

			MvcResult reissueResult =
					mockMvc
							.perform(
									post("/api/v1/auth/reissue")
											.header("Client-Type", "WEB")
											.cookie(new jakarta.servlet.http.Cookie("rt", rtCookie)))
							.andExpect(status().isOk())
							.andReturn();

			String newAt = getCookieValue(reissueResult.getResponse(), "at");
			String newRt = getCookieValue(reissueResult.getResponse(), "rt");
			assertThat(newAt).isNotBlank();
			assertThat(newRt).isNotBlank();
		}

		@Test
		@DisplayName("WEB: rt 쿠키 없이 → 401")
		void web_reissue_without_cookie() throws Exception {
			mockMvc
					.perform(post("/api/v1/auth/reissue").header("Client-Type", "WEB"))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("APP: refreshToken body로 재발급 → 새 AT + RT body")
		void app_reissue_with_body_rt() throws Exception {
			signup("reissue_app01");
			MvcResult loginResult = login("reissue_app01", "APP");
			String rt =
					objectMapper
							.readValue(loginResult.getResponse().getContentAsString(), Map.class)
							.get("refreshToken")
							.toString();

			mockMvc
					.perform(
							post("/api/v1/auth/reissue")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Client-Type", "APP")
									.content("{\"refreshToken\":\"" + rt + "\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.accessToken").isNotEmpty())
					.andExpect(jsonPath("$.refreshToken").isNotEmpty());
		}

		@Test
		@DisplayName("AT를 RT로 사용 시도 → 401 (token_type 검증)")
		void reissue_with_access_token_as_rt_fails() throws Exception {
			signup("reissue_wrong01");
			MvcResult loginResult = login("reissue_wrong01", "APP");
			// AT를 RT 자리에 넣음
			String at =
					objectMapper
							.readValue(loginResult.getResponse().getContentAsString(), Map.class)
							.get("accessToken")
							.toString();

			mockMvc
					.perform(
							post("/api/v1/auth/reissue")
									.contentType(MediaType.APPLICATION_JSON)
									.header("Client-Type", "APP")
									.content("{\"refreshToken\":\"" + at + "\"}"))
					.andExpect(status().isUnauthorized());
		}
	}

	// ──────────────────────────────────────────────────────────
	// 로그아웃
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/auth/logout")
	class LogoutTest {

		@Test
		@DisplayName("WEB: 로그아웃 시 at + rt 쿠키 Max-Age=0")
		void web_logout_deletes_cookies() throws Exception {
			signup("logout_web01");
			login("logout_web01", "WEB");

			MvcResult result =
					mockMvc
							.perform(post("/api/v1/auth/logout").header("Client-Type", "WEB"))
							.andExpect(status().isOk())
							.andReturn();

			String setCookieHeaders = String.join(",", result.getResponse().getHeaders("Set-Cookie"));
			assertThat(setCookieHeaders).containsIgnoringCase("at=");
			assertThat(setCookieHeaders).containsIgnoringCase("Max-Age=0");
		}

		@Test
		@DisplayName("로그아웃은 멱등 — 쿠키 없어도 200")
		void logout_idempotent() throws Exception {
			mockMvc
					.perform(post("/api/v1/auth/logout").header("Client-Type", "WEB"))
					.andExpect(status().isOk());
		}
	}

	// ──────────────────────────────────────────────────────────
	// JWKS
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("GET /oauth2/jwks")
	class JwksTest {

		@Test
		@DisplayName("공개키 1개 이상 반환, kid 포함")
		void jwks_returns_public_keys() throws Exception {
			mockMvc
					.perform(get("/oauth2/jwks"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.keys").isArray())
					.andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
					.andExpect(jsonPath("$.keys[0].n").isNotEmpty()); // RSA modulus
		}

		@Test
		@DisplayName("인증 없이 접근 가능 (public endpoint)")
		void jwks_accessible_without_auth() throws Exception {
			mockMvc.perform(get("/oauth2/jwks")).andExpect(status().isOk());
		}
	}

	// ──────────────────────────────────────────────────────────
	// OAuth 클라이언트 등록 (Admin API)
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/admin/clients")
	class AdminClientRegistrationTest {

		@Test
		@DisplayName("authorization_code 등록 → clientId 반환, clientSecret 없음")
		void register_authorization_code_client() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-Internal-Api-Key", "test-internal-key")
									.content(
											"""
											{
												"grantType": "authorization_code",
												"clientName": "EEOS 웹앱",
												"redirectUris": ["https://app.econovation.kr/callback"]
											}
											"""))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").isNotEmpty())
					.andExpect(jsonPath("$.clientSecret").doesNotExist());
		}

		@Test
		@DisplayName("client_credentials 등록 → clientId + clientSecret 1회 반환")
		void register_client_credentials_returns_secret_once() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-Internal-Api-Key", "test-internal-key")
									.content(
											"""
											{
												"grantType": "client_credentials",
												"clientName": "EEOS 서버",
												"upstreamUrl": "http://eeos-server:8080",
												"pathPrefix": "/api/eeos"
											}
											"""))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.clientId").isNotEmpty())
					.andExpect(jsonPath("$.clientSecret").isNotEmpty())
					.andExpect(jsonPath("$.routeId").isNotEmpty());
		}

		@Test
		@DisplayName("X-Internal-Api-Key 없이 → 401")
		void register_without_api_key_returns_401() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.content(
											"""
											{"grantType":"authorization_code","clientName":"x",
											"redirectUris":["http://localhost"]}
											"""))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("잘못된 X-Internal-Api-Key → 401")
		void register_wrong_api_key_returns_401() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-Internal-Api-Key", "wrong-key")
									.content(
											"""
											{"grantType":"authorization_code","clientName":"x",
											"redirectUris":["http://localhost"]}
											"""))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("authorization_code에 redirectUris 없으면 400")
		void register_authorization_code_without_redirect_uris() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-Internal-Api-Key", "test-internal-key")
									.content(
											"""
											{"grantType":"authorization_code","clientName":"no-redirect"}
											"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("REDIRECT_URI_REQUIRED"));
		}

		@Test
		@DisplayName("clientName 중복 등록 → 409")
		void register_duplicate_client_name_returns_409() throws Exception {
			String body =
					"""
					{"grantType":"authorization_code","clientName":"중복테스트앱",
					"redirectUris":["http://localhost"]}
					""";
			mockMvc.perform(
					post("/api/v1/admin/clients")
							.contentType(MediaType.APPLICATION_JSON)
							.header("X-Internal-Api-Key", "test-internal-key")
							.content(body));
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-Internal-Api-Key", "test-internal-key")
									.content(body))
					.andExpect(status().isConflict());
		}

		@Test
		@DisplayName("지원하지 않는 grantType → 400")
		void register_unsupported_grant_type() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/admin/clients")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-Internal-Api-Key", "test-internal-key")
									.content(
											"""
											{"grantType":"implicit","clientName":"bad-app"}
											"""))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_GRANT_TYPE"));
		}
	}

	// ──────────────────────────────────────────────────────────
	// redirectUri 관리
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("redirectUri CRUD")
	class RedirectUriManagementTest {

		@Test
		@DisplayName("redirectUri 추가 → 목록에 반영")
		void add_redirect_uri() throws Exception {
			// 클라이언트 등록
			MvcResult regResult =
					mockMvc
							.perform(
									post("/api/v1/admin/clients")
											.contentType(MediaType.APPLICATION_JSON)
											.header("X-Internal-Api-Key", "test-internal-key")
											.content(
													"""
													{"grantType":"authorization_code","clientName":"uri-test-app",
													"redirectUris":["https://app.econovation.kr/callback"]}
													"""))
							.andReturn();
			String clientId =
					objectMapper
							.readValue(regResult.getResponse().getContentAsString(), Map.class)
							.get("clientId")
							.toString();

			// URI 추가
			mockMvc
					.perform(
							post("/api/v1/admin/clients/" + clientId + "/redirect-uris")
									.contentType(MediaType.APPLICATION_JSON)
									.header("X-Internal-Api-Key", "test-internal-key")
									.content("{\"uri\":\"https://dev.econovation.kr/callback\"}"))
					.andExpect(status().isOk());

			// 조회로 확인
			mockMvc
					.perform(
							get("/api/v1/admin/clients/" + clientId)
									.header("X-Internal-Api-Key", "test-internal-key"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.redirectUris").isArray());
		}
	}

	// ──────────────────────────────────────────────────────────
	// 라우트 목록
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("GET /api/v1/admin/routes")
	class RoutesTest {

		@Test
		@DisplayName("올바른 키 → 200 + routes 배열")
		void get_routes_with_valid_key() throws Exception {
			mockMvc
					.perform(get("/api/v1/admin/routes").header("X-Internal-Api-Key", "test-internal-key"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.routes").isArray());
		}

		@Test
		@DisplayName("키 없이 → 401")
		void get_routes_without_key() throws Exception {
			mockMvc.perform(get("/api/v1/admin/routes")).andExpect(status().isUnauthorized());
		}
	}

	// ──────────────────────────────────────────────────────────
	// 회원 정보 조회
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("POST /api/v1/members/query")
	class MemberInfoTest {

		@Test
		@DisplayName("단건: ids 1개 → 결과 1개, 이름/loginId 포함")
		void query_single_member() throws Exception {
			signup("memberinfo01");
			Long memberId = extractMemberIdFromToken(login("memberinfo01", "APP"));

			mockMvc
					.perform(
							post("/api/v1/members/query")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"ids\":[" + memberId + "]}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$").isArray())
					.andExpect(jsonPath("$[0].memberId").value(memberId))
					.andExpect(jsonPath("$[0].name").value("memberinfo01"))
					.andExpect(jsonPath("$[0].loginId").value("memberinfo01"));
		}

		@Test
		@DisplayName("다건: ids 여러개 → 존재하는 것만 반환")
		void query_multiple_members() throws Exception {
			signup("memberinfo_a");
			signup("memberinfo_b");

			Long id1 = extractMemberIdFromToken(login("memberinfo_a", "APP"));
			Long id2 = extractMemberIdFromToken(login("memberinfo_b", "APP"));

			mockMvc
					.perform(
							post("/api/v1/members/query")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"ids\":[" + id1 + "," + id2 + "]}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(2));
		}

		@Test
		@DisplayName("없는 ID 포함해도 200 — 존재하는 것만 반환")
		void query_nonexistent_id_returns_empty_list() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/members/query")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"ids\":[999999999]}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$").isArray())
					.andExpect(jsonPath("$.length()").value(0));
		}

		@Test
		@DisplayName("ids 빈 배열 → 400")
		void query_empty_ids_returns_400() throws Exception {
			mockMvc
					.perform(
							post("/api/v1/members/query")
									.contentType(MediaType.APPLICATION_JSON)
									.content("{\"ids\":[]}"))
					.andExpect(status().isBadRequest());
		}
	}

	// ──────────────────────────────────────────────────────────
	// 헬퍼
	// ──────────────────────────────────────────────────────────

	private void signup(String loginId) throws Exception {
		mockMvc.perform(
				post("/api/v1/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(
								String.format(
										"""
										{"name":"%s","loginId":"%s","password":"Econo1234!","generation":30,"status":"AM"}
										""",
										loginId, loginId)));
	}

	private MvcResult login(String loginId, String clientType) throws Exception {
		return mockMvc
				.perform(
						post("/api/v1/auth/login")
								.contentType(MediaType.APPLICATION_JSON)
								.header("Client-Type", clientType)
								.content(
										String.format(
												"""
												{"loginId":"%s","password":"Econo1234!"}
												""",
												loginId)))
				.andReturn();
	}

	/**
	 * TokenCookieManager가 response.addHeader(SET_COOKIE, ...) 방식을 사용하므로 response.getCookie()가 아닌
	 * Set-Cookie 헤더를 직접 파싱해야 한다.
	 */
	/** APP 로그인 응답에서 JWT의 memberId 클레임을 추출한다. */
	private Long extractMemberIdFromToken(MvcResult loginResult) throws Exception {
		String at =
				objectMapper
						.readValue(loginResult.getResponse().getContentAsString(), Map.class)
						.get("accessToken")
						.toString();
		// JWT payload 디코딩 (Base64URL, padding 없음)
		String payload = at.split("\\.")[1];
		int pad = (4 - payload.length() % 4) % 4;
		byte[] decoded = java.util.Base64.getDecoder().decode(payload + "=".repeat(pad));
		Map<?, ?> claims = objectMapper.readValue(new String(decoded), Map.class);
		return ((Number) claims.get("memberId")).longValue();
	}

	private String getCookieValue(MockHttpServletResponse response, String name) {
		return response.getHeaders("Set-Cookie").stream()
				.filter(h -> h.startsWith(name + "="))
				.map(h -> h.split(";")[0].substring(name.length() + 1))
				.findFirst()
				.orElse(null);
	}

	// ──────────────────────────────────────────────────────────
	// 테스트용 RSA 키 (테스트 전용, 운영 키와 무관)
	// ──────────────────────────────────────────────────────────

	static final String TEST_PRIVATE_KEY_PEM =
			"""
			-----BEGIN PRIVATE KEY-----
			MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDgQZ4BgnPg0DtR
			efifiW5CZnDH0ZU2hZapTJU92oE2OU6ytHZHYbrPJUr0sPqioYuBwpMrjHQzRrs0
			L6pzDWETYhe0Tfx7aIZ3Akzh/ksOHatyyOY93ZgKXEn8Vf68A8z+gfD3Mnez9O6D
			9gU0bEYcOmRInMnqZa5+UWJhtzE6TTwdYAOPOTVILQscA4FhbUh1HdTXLro7QD/c
			DhpHfPZ9ib/ONxhQFCiZtpYQ5vupKDi7EIg767E9UXeP4srHafiH3LwKS93mNzAY
			y4/LEiLEwa0WClLj1LoUMN46L6MU3sjD+PABKObPeF7vy3gqhhTFX/7ylxwsf73k
			osiFx5CbAgMBAAECggEACrVnb6gEk4wuAPaDlk6wOrB8mzYZW3immKAFEmaQPAAh
			j2wJp9/1VTXxLd0Totd2+3PIplA3Lkmm39eU2piddZj+C8ibkWMM5Lrx4+rKN7rX
			+Iu6XuLpgzkj+U4780t1KwQWdx9sUuYTy65gUkAHWwq4xjTieXRwkCyft43RZni5
			z2g89DCewBOiQqwAFpOS5IAQaXhWS653V/3BvbUWRq2nCz6Ii17tavxf2cMo48FP
			047pwKmlUgbWu7ukVifzbuJ3gbsnLdbCCGlovGia1SAVmpgWc+YXc69iPlvPqmfF
			iJwCPtaiPlVljNjYeEcMWzFmTXuZ4sPHFMB6BBJ0JQKBgQDxQpVLHBKTgGAuEDsL
			T1IgWbZ5Moy3oWdGbEx9pPqiBWzhyc12XKuDKkQzrEcEf9OAKwt27kQSDQvWsBzR
			M4o1+E/vDmR1WSrJBqL77Idbyrve5XqbSxlEuTzfnknhz6g3M1z2T0MWkOzg7N4U
			OSTKK6L9e87RjNY3RZR+fYcI7wKBgQDt9RZbpq4kZwmn4/cGu0z6C91n3qaxKlRp
			bWA1E330c91pVbe66IgI333ujMn1XmdybYVEYfM1XQE4cp5WB9iYUsd2N3FgC1C3
			y7tHHONuXbSZFeDR+prfWSq54BM8KRQNljWSJVSbm3XyZWKBVzjpPHZ+GWm9KGGX
			64lBxCd7FQKBgEyvTELefm0R7gBWOl5C7SjJOz3UunOXwvrYa4u4R83+CXjPKxaH
			KCD960W9aaldc/2WMQOxgkN2kB/CRLaeOj0jW0zx9y5xlC7nhCCtMu0TSJn1uP3p
			ZfO5KUcuye8TkTVShybnVLy0fgaUY5Zr/2yfaCRIGRn0ORbCcAuwnJ5TAoGBAJQv
			9g17fqVC2ZuJdvPlPqnVK7ucx7g7AThqmehTzDOo2DDZ5cJrPxQvgjBF0xuP/+Id
			3ElaqmgIFFN/5aTz2+n4Wyj+nAdQ5KKhKG6/yc6YYniXTFvXsgz3bYk1xyTG/Cr9
			6TyrLMZ/CPO8OZ0MoW92bOBYmSXoeOZwExk2u9OFAoGAIM6gzQe2xnfCA5dHs4w8
			Xj5/jZLi6LxcKyeH+FUyM38sfZQfX+fVfChGlgA4uJbSmjUV6+LVRBPSw+rZDWC6
			juXSNr0JIcJSJTmuuIYLmqFGIfuA7aRnp/gHPPFTq/3M6rU/NyvOfP9xFkWiIL6k
			boeQ9oT8oU5qParLDE3gO5s=
			-----END PRIVATE KEY-----
			""";

	static final String TEST_PUBLIC_KEY_PEM =
			"""
			-----BEGIN PUBLIC KEY-----
			MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4EGeAYJz4NA7UXn4n4lu
			QmZwx9GVNoWWqUyVPdqBNjlOsrR2R2G6zyVK9LD6oqGLgcKTK4x0M0a7NC+qcw1h
			E2IXtE38e2iGdwJM4f5LDh2rcsjmPd2YClxJ/FX+vAPM/oHw9zJ3s/Tug/YFNGxG
			HDpkSJzJ6mWuflFiYbcxOk08HWADjzk1SC0LHAOBYW1IdR3U1y66O0A/3A4aR3z2
			fYm/zjcYUBQombaWEOb7qSg4uxCIO+uxPVF3j+LKx2n4h9y8Ckvd5jcwGMuPyxIi
			xMGtFgpS49S6FDDeOi+jFN7Iw/jwASjmz3he78t4KoYUxV/+8pccLH+95KLIhceQ
			mwIDAQAB
			-----END PUBLIC KEY-----
			""";
}
