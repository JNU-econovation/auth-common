package com.econo.auth.client.application.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import com.econo.auth.client.application.port.out.SasClientRegistrar;
import com.econo.auth.client.application.port.out.ServiceClientRepository;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult;
import com.econo.auth.client.domain.ServiceClient;
import com.econo.auth.client.exception.DuplicateClientNameException;
import com.econo.auth.client.exception.RedirectUriRequiredException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RegisterOAuthClientService 단위 테스트 */
@ExtendWith(MockitoExtension.class)
class RegisterOAuthClientServiceTest {

	@Mock private SasClientRegistrar sasClientRegistrar;
	@Mock private ServiceClientRepository serviceClientRepository;

	private RegisterOAuthClientService service;

	@BeforeEach
	void setUp() {
		service = new RegisterOAuthClientService(sasClientRegistrar, serviceClientRepository);
	}

	// ──────────────────────────────────────────────────────────
	// 등록 성공
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("클라이언트 등록 성공")
	class RegisterSuccessTest {

		@Test
		@DisplayName("등록 성공 시 SAS 등록 + ServiceClient 저장 호출, clientId 반환")
		void register_callsSasAndRepository() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand("테스트 SPA", Set.of("http://localhost:3000/callback"));

			RegisterOAuthClientResult result = service.register(command);

			then(sasClientRegistrar)
					.should(times(1))
					.registerAuthorizationCodeClient(anyString(), eq("테스트 SPA"), any());
			then(serviceClientRepository).should(times(1)).save(any(ServiceClient.class));
			assertThat(result.clientId()).isNotBlank();
		}
	}

	// ──────────────────────────────────────────────────────────
	// 유효성 검증
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("유효성 검증")
	class ValidationTest {

		@Test
		@DisplayName("redirectUris가 null이면 RedirectUriRequiredException 발생")
		void register_withNullRedirectUris_throwsException() {
			RegisterOAuthClientCommand command = new RegisterOAuthClientCommand("테스트 SPA", null);

			assertThatThrownBy(() -> service.register(command))
					.isInstanceOf(RedirectUriRequiredException.class);
			then(sasClientRegistrar).should(never()).registerAuthorizationCodeClient(any(), any(), any());
		}

		@Test
		@DisplayName("redirectUris가 빈 Set이면 RedirectUriRequiredException 발생")
		void register_withEmptyRedirectUris_throwsException() {
			RegisterOAuthClientCommand command = new RegisterOAuthClientCommand("테스트 SPA", Set.of());

			assertThatThrownBy(() -> service.register(command))
					.isInstanceOf(RedirectUriRequiredException.class);
		}

		@Test
		@DisplayName("clientName이 null이면 IllegalArgumentException 발생")
		void register_withNullClientName_throwsException() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(null, Set.of("http://localhost:3000/callback"));

			assertThatThrownBy(() -> service.register(command))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	// ──────────────────────────────────────────────────────────
	// 중복 방지
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("중복 클라이언트 이름 방지")
	class DuplicateClientTest {

		@Test
		@DisplayName("이미 존재하는 clientName으로 등록 시 DuplicateClientNameException 발생")
		void register_withDuplicateClientName_throwsException() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand("이미있는서비스", Set.of("http://localhost:3000/callback"));
			given(serviceClientRepository.existsByClientName("이미있는서비스")).willReturn(true);

			assertThatThrownBy(() -> service.register(command))
					.isInstanceOf(DuplicateClientNameException.class);
			then(sasClientRegistrar).should(never()).registerAuthorizationCodeClient(any(), any(), any());
		}
	}
}
