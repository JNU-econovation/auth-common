package com.econo.auth.client.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import com.econo.auth.client.application.repository.SasRedirectUriManager;
import com.econo.auth.client.application.repository.ServiceClientRepository;
import com.econo.auth.client.application.usecase.ClientRedirectUriUseCase;
import com.econo.auth.client.exception.InvalidClientException;
import com.econo.auth.client.exception.RedirectUriRequiredException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ClientRedirectUriService 단위 테스트 */
@ExtendWith(MockitoExtension.class)
class ClientRedirectUriServiceTest {

	@Mock private SasRedirectUriManager sasRedirectUriManager;
	@Mock private ServiceClientRepository serviceClientRepository;

	private ClientRedirectUriService service;

	@BeforeEach
	void setUp() {
		service = new ClientRedirectUriService(sasRedirectUriManager, serviceClientRepository);
	}

	// ──────────────────────────────────────────────────────────
	// findByClientId
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("findByClientId")
	class FindByClientIdTest {

		@Test
		@DisplayName("존재하는 클라이언트 조회 → ClientInfo 반환")
		void findByClientId_success() {
			given(sasRedirectUriManager.findRedirectUrisByClientId("cid"))
					.willReturn(Set.of("https://app.example.com/cb"));
			given(sasRedirectUriManager.findClientNameByClientId("cid")).willReturn("테스트앱");

			ClientRedirectUriUseCase.ClientInfo info = service.findByClientId("cid");

			assertThat(info.clientId()).isEqualTo("cid");
			assertThat(info.clientName()).isEqualTo("테스트앱");
			assertThat(info.redirectUris()).containsExactly("https://app.example.com/cb");
		}

		@Test
		@DisplayName("존재하지 않는 clientId → InvalidClientException")
		void findByClientId_notFound() {
			given(sasRedirectUriManager.findRedirectUrisByClientId("unknown")).willReturn(null);

			assertThatThrownBy(() -> service.findByClientId("unknown"))
					.isInstanceOf(InvalidClientException.class);
		}
	}

	// ──────────────────────────────────────────────────────────
	// addRedirectUri
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("addRedirectUri")
	class AddRedirectUriTest {

		@Test
		@DisplayName("정상 URI 추가 → 업데이트 호출 + 새 목록 반환")
		void addRedirectUri_success() {
			given(sasRedirectUriManager.findRedirectUrisByClientId("cid"))
					.willReturn(new HashSet<>(Set.of("https://existing.example.com/cb")));

			Set<String> result = service.addRedirectUri("cid", "https://new.example.com/cb");

			assertThat(result).contains("https://new.example.com/cb", "https://existing.example.com/cb");
			then(sasRedirectUriManager).should().updateRedirectUris(eq("cid"), any());
		}

		@Test
		@DisplayName("10개 초과 시 RedirectUriRequiredException")
		void addRedirectUri_maxExceeded() {
			Set<String> full = new HashSet<>();
			for (int i = 0; i < 10; i++) full.add("https://app" + i + ".example.com/cb");
			given(sasRedirectUriManager.findRedirectUrisByClientId("cid")).willReturn(full);

			assertThatThrownBy(() -> service.addRedirectUri("cid", "https://new.example.com/cb"))
					.isInstanceOf(RedirectUriRequiredException.class);
		}

		@Test
		@DisplayName("존재하지 않는 clientId → InvalidClientException")
		void addRedirectUri_clientNotFound() {
			given(sasRedirectUriManager.findRedirectUrisByClientId("unknown")).willReturn(null);

			assertThatThrownBy(() -> service.addRedirectUri("unknown", "https://app.example.com/cb"))
					.isInstanceOf(InvalidClientException.class);
		}

		@Test
		@DisplayName("http:// 스킴 URI도 허용")
		void addRedirectUri_httpSchemeAllowed() {
			given(sasRedirectUriManager.findRedirectUrisByClientId("cid"))
					.willReturn(new HashSet<>(Set.of("https://existing.example.com/cb")));

			assertThatCode(() -> service.addRedirectUri("cid", "http://localhost:3000/cb"))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("유효하지 않은 스킴(ftp://) → RedirectUriRequiredException (validate()에서 사전 차단)")
		void addRedirectUri_invalidScheme() {
			// validate()가 findRedirectUrisByClientId() 호출 전에 예외 발생 → stub 불필요
			assertThatThrownBy(() -> service.addRedirectUri("cid", "ftp://example.com/cb"))
					.isInstanceOf(RedirectUriRequiredException.class);
		}

		@Test
		@DisplayName("512자 초과 URI → RedirectUriRequiredException (validate()에서 사전 차단)")
		void addRedirectUri_tooLongUri() {
			String longUri = "https://example.com/" + "a".repeat(510);

			assertThatThrownBy(() -> service.addRedirectUri("cid", longUri))
					.isInstanceOf(RedirectUriRequiredException.class);
		}
	}

	// ──────────────────────────────────────────────────────────
	// removeRedirectUri
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("removeRedirectUri")
	class RemoveRedirectUriTest {

		@Test
		@DisplayName("정상 삭제 → 업데이트 호출 + 남은 목록 반환")
		void removeRedirectUri_success() {
			given(sasRedirectUriManager.findRedirectUrisByClientId("cid"))
					.willReturn(
							new HashSet<>(Set.of("https://app.example.com/cb", "https://dev.example.com/cb")));

			Set<String> result = service.removeRedirectUri("cid", "https://dev.example.com/cb");

			assertThat(result).containsExactly("https://app.example.com/cb");
		}

		@Test
		@DisplayName("마지막 URI 삭제 시도 → RedirectUriRequiredException")
		void removeRedirectUri_lastUri() {
			given(sasRedirectUriManager.findRedirectUrisByClientId("cid"))
					.willReturn(new HashSet<>(Set.of("https://app.example.com/cb")));

			assertThatThrownBy(() -> service.removeRedirectUri("cid", "https://app.example.com/cb"))
					.isInstanceOf(RedirectUriRequiredException.class);
		}

		@Test
		@DisplayName("미등록 URI 삭제 시도 → RedirectUriRequiredException")
		void removeRedirectUri_notFound() {
			given(sasRedirectUriManager.findRedirectUrisByClientId("cid"))
					.willReturn(new HashSet<>(Set.of("https://app.example.com/cb")));

			assertThatThrownBy(() -> service.removeRedirectUri("cid", "https://other.example.com/cb"))
					.isInstanceOf(RedirectUriRequiredException.class);
		}
	}

	// ──────────────────────────────────────────────────────────
	// replaceRedirectUris
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("replaceRedirectUris")
	class ReplaceRedirectUrisTest {

		@Test
		@DisplayName("정상 교체 → 업데이트 호출 + 새 목록 반환")
		void replaceRedirectUris_success() {
			given(sasRedirectUriManager.findRedirectUrisByClientId("cid"))
					.willReturn(Set.of("https://old.example.com/cb"));

			Set<String> newUris = Set.of("https://new1.example.com/cb", "https://new2.example.com/cb");
			Set<String> result = service.replaceRedirectUris("cid", newUris);

			assertThat(result).isEqualTo(newUris);
			then(sasRedirectUriManager).should().updateRedirectUris("cid", newUris);
		}

		@Test
		@DisplayName("빈 목록으로 교체 시도 → RedirectUriRequiredException")
		void replaceRedirectUris_emptyList() {
			assertThatThrownBy(() -> service.replaceRedirectUris("cid", Set.of()))
					.isInstanceOf(RedirectUriRequiredException.class);
		}

		@Test
		@DisplayName("10개 초과 목록으로 교체 시도 → RedirectUriRequiredException")
		void replaceRedirectUris_tooMany() {
			Set<String> tooMany = new HashSet<>();
			for (int i = 0; i < 11; i++) tooMany.add("https://app" + i + ".example.com/cb");

			assertThatThrownBy(() -> service.replaceRedirectUris("cid", tooMany))
					.isInstanceOf(RedirectUriRequiredException.class);
		}
	}

	// ──────────────────────────────────────────────────────────
	// extractOrigin (static)
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("extractOrigin (오리진 추출)")
	class ExtractOriginTest {

		@Test
		@DisplayName("https URI → scheme+host만 추출")
		void extractOrigin_https() {
			assertThat(ClientRedirectUriService.extractOrigin("https://app.example.com/callback"))
					.isEqualTo("https://app.example.com");
		}

		@Test
		@DisplayName("포트 포함 URI → scheme+host+port 추출")
		void extractOrigin_withPort() {
			assertThat(ClientRedirectUriService.extractOrigin("http://localhost:3000/callback"))
					.isEqualTo("http://localhost:3000");
		}

		@Test
		@DisplayName("잘못된 URI 형식(scheme 없음) → null 반환 안 함, 예외도 없음")
		void extractOrigin_relativeUri_noException() {
			// URI.create("not-a-uri")는 유효한 상대 URI — scheme이 null이므로 "null://null" 반환
			// extractOrigin은 예외 없이 처리하고, 실제 null 반환은 IllegalArgumentException 시에만 발생
			assertThatCode(() -> ClientRedirectUriService.extractOrigin("not-a-uri"))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("완전히 파싱 불가능한 URI → null 반환")
		void extractOrigin_unparseable() {
			// URI.create() 자체가 IllegalArgumentException 던지는 경우 → catch → null
			assertThat(ClientRedirectUriService.extractOrigin("://invalid::uri")).isNull();
		}

		@Test
		@DisplayName("extractAllowedOrigins — redirectUri 오리진 + additionalOrigins 합산")
		void extractAllowedOrigins_mergesWithAdditional() {
			given(serviceClientRepository.findAllRegisteredClientIds()).willReturn(List.of("cid1"));
			given(sasRedirectUriManager.findRedirectUrisByClientId("cid1"))
					.willReturn(Set.of("https://app.example.com/callback"));

			Set<String> result = service.extractAllowedOrigins(Set.of("https://extra.example.com"));

			assertThat(result).contains("https://app.example.com", "https://extra.example.com");
		}
	}
}
