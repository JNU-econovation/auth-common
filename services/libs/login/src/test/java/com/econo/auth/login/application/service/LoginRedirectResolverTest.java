package com.econo.auth.login.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.econo.auth.client.application.usecase.ClientRedirectUriUseCase;
import com.econo.auth.client.application.usecase.ClientRedirectUriUseCase.ClientInfo;
import com.econo.auth.client.exception.InvalidClientException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** LoginRedirectResolver 단위 테스트 — clientId 기반 redirect_uri 결정 로직 전수 검증 */
@DisplayName("LoginRedirectResolver — clientId 기반 redirect_uri 결정 단위 테스트")
@ExtendWith(MockitoExtension.class)
class LoginRedirectResolverTest {

	@Mock private ClientRedirectUriUseCase clientRedirectUriUseCase;

	private LoginRedirectResolver resolver;

	private static final String DEFAULT_URL = "http://localhost:3000";

	@BeforeEach
	void setUp() {
		resolver = new LoginRedirectResolver(clientRedirectUriUseCase);
	}

	@Nested
	@DisplayName("resolve — clientId 기반 redirect_uri 결정")
	class ResolveTest {

		@Test
		@DisplayName("null clientId → defaultUrl 반환")
		void nullClientId_returnsDefaultUrl() {
			// when
			String result = resolver.resolve(null, DEFAULT_URL);

			// then
			assertThat(result).isEqualTo(DEFAULT_URL);
		}

		@Test
		@DisplayName("빈 문자열 clientId → defaultUrl 반환")
		void blankClientId_returnsDefaultUrl() {
			// when
			String result = resolver.resolve("", DEFAULT_URL);

			// then
			assertThat(result).isEqualTo(DEFAULT_URL);
		}

		@Test
		@DisplayName("공백만 있는 clientId → defaultUrl 반환")
		void whitespaceClientId_returnsDefaultUrl() {
			// when
			String result = resolver.resolve("   ", DEFAULT_URL);

			// then
			assertThat(result).isEqualTo(DEFAULT_URL);
		}

		@Test
		@DisplayName("미등록 clientId(InvalidClientException) → defaultUrl 반환 (4xx 거부 없음)")
		void unregisteredClientId_returnsDefaultUrl() {
			// given
			given(clientRedirectUriUseCase.findByClientId("unknown-client"))
					.willThrow(new InvalidClientException());

			// when
			String result = resolver.resolve("unknown-client", DEFAULT_URL);

			// then
			assertThat(result).isEqualTo(DEFAULT_URL);
		}

		@Test
		@DisplayName("redirect_uri 1개인 클라이언트 → 해당 URI 반환")
		void singleRedirectUri_returnsThatUri() {
			// given
			String clientId = "single-uri-client";
			String expectedUri = "https://app.example.com/callback";
			ClientInfo clientInfo = new ClientInfo(clientId, "단일URI앱", Set.of(expectedUri));
			given(clientRedirectUriUseCase.findByClientId(clientId)).willReturn(clientInfo);

			// when
			String result = resolver.resolve(clientId, DEFAULT_URL);

			// then
			assertThat(result).isEqualTo(expectedUri);
		}

		@Test
		@DisplayName("redirect_uri 여러 개인 클라이언트 → 알파벳 정렬 후 첫 번째 URI 반환")
		void multipleRedirectUris_returnsAlphabeticallyFirst() {
			// given
			String clientId = "multi-uri-client";
			// Set.of는 순서를 보장하지 않으므로 정렬 로직을 실제로 검증
			ClientInfo clientInfo =
					new ClientInfo(
							clientId, "복수URI앱", Set.of("https://z.example.com/cb", "https://a.example.com/cb"));
			given(clientRedirectUriUseCase.findByClientId(clientId)).willReturn(clientInfo);

			// when
			String result = resolver.resolve(clientId, DEFAULT_URL);

			// then — 알파벳 오름차순 정렬 기준 "https://a.example.com/cb"가 첫 번째
			assertThat(result).isEqualTo("https://a.example.com/cb");
		}

		@Test
		@DisplayName("redirect_uri Set이 비어 있는 클라이언트 → defaultUrl 반환")
		void emptyRedirectUriSet_returnsDefaultUrl() {
			// given
			String clientId = "empty-uri-client";
			ClientInfo clientInfo = new ClientInfo(clientId, "빈URI앱", Set.of());
			given(clientRedirectUriUseCase.findByClientId(clientId)).willReturn(clientInfo);

			// when
			String result = resolver.resolve(clientId, DEFAULT_URL);

			// then
			assertThat(result).isEqualTo(DEFAULT_URL);
		}
	}
}
