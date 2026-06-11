package com.econo.auth.api.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * LoginResponse 단위 테스트 — redirectUrl 필드 추가 및 팩토리 메서드 검증
 *
 * <p>커버 범위:
 *
 * <ul>
 *   <li>app(3인자) 팩토리 — redirectUrl=null (하위 호환)
 *   <li>app(4인자) 팩토리 — redirectUrl 포함 (신규)
 *   <li>@JsonInclude(NON_NULL) — redirectUrl null이면 직렬화 제외
 *   <li>@JsonInclude(NON_NULL) — redirectUrl non-null이면 직렬화 포함
 * </ul>
 */
class LoginResponseTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Nested
	@DisplayName("app() 팩토리 메서드 — redirectUrl 필드")
	class AppFactoryMethodTest {

		@Test
		@DisplayName("app(4인자) — redirectUrl 포함 응답 생성")
		void app_withRedirectUrl_setsRedirectUrl() {
			// when
			LoginResponse response =
					LoginResponse.app(
							"access-token", 1700000000L, "refresh-token", "https://redirect.example.com");

			// then
			assertThat(response.redirectUrl()).isEqualTo("https://redirect.example.com");
			assertThat(response.accessToken()).isEqualTo("access-token");
			assertThat(response.refreshToken()).isEqualTo("refresh-token");
			assertThat(response.accessExpiredTime()).isEqualTo(1700000000L);
		}

		@Test
		@DisplayName("app(3인자) — 기존 시그니처 하위 호환 유지 (redirectUrl=null)")
		void app_withoutRedirectUrl_redirectUrlIsNull() {
			// when
			LoginResponse response = LoginResponse.app("access-token", 1700000000L, "refresh-token");

			// then
			assertThat(response.redirectUrl()).isNull();
			assertThat(response.accessToken()).isEqualTo("access-token");
		}
	}

	@Nested
	@DisplayName("@JsonInclude(NON_NULL) — redirectUrl 직렬화")
	class JsonSerializationTest {

		@Test
		@DisplayName("redirectUrl이 non-null이면 JSON에 포함됨")
		void serialize_withRedirectUrl_includesRedirectUrl() throws Exception {
			// given
			LoginResponse response =
					LoginResponse.app(
							"access-token", 1700000000L, "refresh-token", "https://app.example.com/callback");

			// when
			String json = objectMapper.writeValueAsString(response);

			// then
			assertThat(json).contains("redirectUrl");
			assertThat(json).contains("https://app.example.com/callback");
		}

		@Test
		@DisplayName("redirectUrl이 null이면 JSON에서 제외됨 (@JsonInclude(NON_NULL))")
		void serialize_withNullRedirectUrl_excludesRedirectUrl() throws Exception {
			// given
			LoginResponse response = LoginResponse.app("access-token", 1700000000L, "refresh-token");

			// when
			String json = objectMapper.writeValueAsString(response);

			// then
			assertThat(json).doesNotContain("redirectUrl");
		}

		@Test
		@DisplayName("redirectUrl을 명시적으로 null 전달해도 JSON에서 제외됨")
		void serialize_withExplicitNullRedirectUrl_excludesRedirectUrl() throws Exception {
			// given
			LoginResponse response =
					LoginResponse.app("access-token", 1700000000L, "refresh-token", null);

			// when
			String json = objectMapper.writeValueAsString(response);

			// then
			assertThat(json).doesNotContain("redirectUrl");
		}

		@Test
		@DisplayName("APP 응답: accessToken + refreshToken + redirectUrl 세 필드 모두 직렬화")
		void serialize_appResponse_containsAllThreeFields() throws Exception {
			// given
			LoginResponse response =
					LoginResponse.app("at-value", 9999L, "rt-value", "https://my-app.com/redirect");

			// when
			String json = objectMapper.writeValueAsString(response);

			// then
			assertThat(json).contains("accessToken");
			assertThat(json).contains("refreshToken");
			assertThat(json).contains("redirectUrl");
			assertThat(json).contains("accessExpiredTime");
		}
	}
}
