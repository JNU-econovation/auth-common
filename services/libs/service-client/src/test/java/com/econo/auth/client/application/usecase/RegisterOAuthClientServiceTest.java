package com.econo.auth.client.application.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import com.econo.auth.client.application.port.out.SasClientRegistrar;
import com.econo.auth.client.application.port.out.ServiceClientRepository;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.SelfRegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.SelfRegisterOAuthClientResult;
import com.econo.auth.client.domain.ServiceClient;
import com.econo.auth.client.exception.ClientLimitExceededException;
import com.econo.auth.client.exception.DuplicateClientNameException;
import com.econo.auth.client.exception.RedirectUriRequiredException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** RegisterOAuthClientService 단위 테스트 */
@ExtendWith(MockitoExtension.class)
class RegisterOAuthClientServiceTest {

	@Mock private SasClientRegistrar sasClientRegistrar;
	@Mock private ServiceClientRepository serviceClientRepository;
	@Mock private PasswordEncoder passwordEncoder;

	private RegisterOAuthClientService service;

	@BeforeEach
	void setUp() {
		service =
				new RegisterOAuthClientService(
						sasClientRegistrar, serviceClientRepository, passwordEncoder);
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

	// ──────────────────────────────────────────────────────────
	// 셀프 등록 — 정상 경로
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("셀프 등록 (selfRegister) — 성공")
	class SelfRegisterSuccessTest {

		@Test
		@DisplayName("정상 등록 시 clientId + clientSecret 반환")
		void selfRegister_success_returnsClientIdAndSecret() {
			// given
			Long ownerId = 123L;
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand("내앱", Set.of("https://my-app.com/callback"), ownerId);
			given(serviceClientRepository.countByOwnerId(ownerId)).willReturn(0L);
			given(serviceClientRepository.existsByClientName("내앱")).willReturn(false);
			given(passwordEncoder.encode(anyString())).willReturn("$2a$12$hashedSecret");

			// when
			SelfRegisterOAuthClientResult result = service.selfRegister(command);

			// then
			assertThat(result.clientId()).isNotBlank();
			assertThat(result.clientSecret()).isNotBlank();
		}

		@Test
		@DisplayName("반환된 clientSecret은 평문이고 저장된 해시와 다름")
		void selfRegister_returnedSecretIsPlaintext_notHash() {
			// given
			Long ownerId = 42L;
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand("앱이름", Set.of("https://example.com/cb"), ownerId);
			given(serviceClientRepository.countByOwnerId(ownerId)).willReturn(2L);
			given(serviceClientRepository.existsByClientName("앱이름")).willReturn(false);
			given(passwordEncoder.encode(anyString())).willReturn("$2a$12$fakeHashValue");

			// when
			SelfRegisterOAuthClientResult result = service.selfRegister(command);

			// then — 결과의 secret은 평문, 저장 시 넘긴 값과 다름
			assertThat(result.clientSecret()).doesNotStartWith("$2a$");
		}

		@Test
		@DisplayName("저장 시 ServiceClient에 ownerId가 포함됨")
		void selfRegister_savesServiceClientWithOwnerId() {
			// given
			Long ownerId = 99L;
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand(
							"소유자앱", Set.of("https://owner.example.com/cb"), ownerId);
			given(serviceClientRepository.countByOwnerId(ownerId)).willReturn(0L);
			given(serviceClientRepository.existsByClientName("소유자앱")).willReturn(false);
			given(passwordEncoder.encode(anyString())).willReturn("$2a$12$ownerHash");

			ArgumentCaptor<ServiceClient> captor = ArgumentCaptor.forClass(ServiceClient.class);

			// when
			service.selfRegister(command);

			// then
			then(serviceClientRepository).should(times(1)).save(captor.capture());
			ServiceClient saved = captor.getValue();
			assertThat(saved.getOwnerId()).isEqualTo(ownerId);
		}

		@Test
		@DisplayName("저장 시 ServiceClient에 clientSecretHash가 포함됨 (BCrypt 해시)")
		void selfRegister_savesServiceClientWithClientSecretHash() {
			// given
			Long ownerId = 77L;
			String expectedHash = "$2a$12$specificBCryptHash";
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand("해시앱", Set.of("https://hash.example.com/cb"), ownerId);
			given(serviceClientRepository.countByOwnerId(ownerId)).willReturn(1L);
			given(serviceClientRepository.existsByClientName("해시앱")).willReturn(false);
			given(passwordEncoder.encode(anyString())).willReturn(expectedHash);

			ArgumentCaptor<ServiceClient> captor = ArgumentCaptor.forClass(ServiceClient.class);

			// when
			service.selfRegister(command);

			// then
			then(serviceClientRepository).should(times(1)).save(captor.capture());
			ServiceClient saved = captor.getValue();
			assertThat(saved.getClientSecretHash()).isEqualTo(expectedHash);
		}

		@Test
		@DisplayName("SAS 등록 및 ServiceClientRepository.save 모두 호출됨")
		void selfRegister_callsSasAndRepository() {
			// given
			Long ownerId = 11L;
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand(
							"SAS호출앱", Set.of("https://sas.example.com/cb"), ownerId);
			given(serviceClientRepository.countByOwnerId(ownerId)).willReturn(0L);
			given(serviceClientRepository.existsByClientName("SAS호출앱")).willReturn(false);
			given(passwordEncoder.encode(anyString())).willReturn("$2a$12$hash");

			// when
			service.selfRegister(command);

			// then
			then(sasClientRegistrar)
					.should(times(1))
					.registerAuthorizationCodeClient(anyString(), eq("SAS호출앱"), any());
			then(serviceClientRepository).should(times(1)).save(any(ServiceClient.class));
		}

		@Test
		@DisplayName("4개 등록 상태에서 추가 등록 성공 (5개 미만)")
		void selfRegister_withFourClients_succeeds() {
			// given
			Long ownerId = 55L;
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand(
							"다섯번째앱", Set.of("https://five.example.com/cb"), ownerId);
			given(serviceClientRepository.countByOwnerId(ownerId)).willReturn(4L);
			given(serviceClientRepository.existsByClientName("다섯번째앱")).willReturn(false);
			given(passwordEncoder.encode(anyString())).willReturn("$2a$12$hashFive");

			// when / then — 예외 없이 성공
			assertThatCode(() -> service.selfRegister(command)).doesNotThrowAnyException();
		}
	}

	// ──────────────────────────────────────────────────────────
	// 셀프 등록 — 5개 초과 제한
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("셀프 등록 — 1인 5개 제한")
	class SelfRegisterLimitTest {

		@Test
		@DisplayName("이미 5개 등록한 회원이 추가 등록 시 ClientLimitExceededException 발생")
		void selfRegister_whenLimitReached_throwsClientLimitExceededException() {
			// given
			Long ownerId = 123L;
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand("초과앱", Set.of("https://over.example.com/cb"), ownerId);
			given(serviceClientRepository.countByOwnerId(ownerId)).willReturn(5L);

			// when / then
			assertThatThrownBy(() -> service.selfRegister(command))
					.isInstanceOf(ClientLimitExceededException.class);
			then(sasClientRegistrar).should(never()).registerAuthorizationCodeClient(any(), any(), any());
			then(serviceClientRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("6개 초과도 ClientLimitExceededException 발생")
		void selfRegister_whenOverLimit_throwsClientLimitExceededException() {
			// given
			Long ownerId = 456L;
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand(
							"초초과앱", Set.of("https://over2.example.com/cb"), ownerId);
			given(serviceClientRepository.countByOwnerId(ownerId)).willReturn(6L);

			// when / then
			assertThatThrownBy(() -> service.selfRegister(command))
					.isInstanceOf(ClientLimitExceededException.class);
		}

		@Test
		@DisplayName("ownerId별 카운트 격리 — 다른 ownerId의 카운트에 영향 없음")
		void selfRegister_countIsIsolatedByOwnerId() {
			// given
			Long ownerA = 100L;
			Long ownerB = 200L;

			// ownerA는 5개 등록 → 초과
			given(serviceClientRepository.countByOwnerId(ownerA)).willReturn(5L);
			// ownerB는 0개 등록 → 성공 가능
			given(serviceClientRepository.countByOwnerId(ownerB)).willReturn(0L);
			given(serviceClientRepository.existsByClientName(anyString())).willReturn(false);
			given(passwordEncoder.encode(anyString())).willReturn("$2a$12$hashB");

			SelfRegisterOAuthClientCommand commandA =
					new SelfRegisterOAuthClientCommand("ownerA앱", Set.of("https://a.example.com/cb"), ownerA);
			SelfRegisterOAuthClientCommand commandB =
					new SelfRegisterOAuthClientCommand("ownerB앱", Set.of("https://b.example.com/cb"), ownerB);

			// when / then
			assertThatThrownBy(() -> service.selfRegister(commandA))
					.isInstanceOf(ClientLimitExceededException.class);
			assertThatCode(() -> service.selfRegister(commandB)).doesNotThrowAnyException();
		}
	}

	// ──────────────────────────────────────────────────────────
	// 셀프 등록 — 유효성 검증
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("셀프 등록 — 유효성 검증")
	class SelfRegisterValidationTest {

		@Test
		@DisplayName("redirectUris가 null이면 RedirectUriRequiredException 발생")
		void selfRegister_withNullRedirectUris_throwsException() {
			// given — 입력값 검증이 DB 조회보다 앞에 있으므로 countByOwnerId stubbing 불필요
			Long ownerId = 1L;
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand("앱이름", null, ownerId);

			// when / then
			assertThatThrownBy(() -> service.selfRegister(command))
					.isInstanceOf(RedirectUriRequiredException.class);
			then(sasClientRegistrar).should(never()).registerAuthorizationCodeClient(any(), any(), any());
		}

		@Test
		@DisplayName("redirectUris가 빈 Set이면 RedirectUriRequiredException 발생")
		void selfRegister_withEmptyRedirectUris_throwsException() {
			// given — 입력값 검증이 DB 조회보다 앞에 있으므로 countByOwnerId stubbing 불필요
			Long ownerId = 2L;
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand("앱이름", Set.of(), ownerId);

			// when / then
			assertThatThrownBy(() -> service.selfRegister(command))
					.isInstanceOf(RedirectUriRequiredException.class);
		}

		@Test
		@DisplayName("clientName 중복 시 DuplicateClientNameException 발생")
		void selfRegister_withDuplicateClientName_throwsException() {
			// given
			Long ownerId = 3L;
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand(
							"중복이름앱", Set.of("https://dup.example.com/cb"), ownerId);
			given(serviceClientRepository.countByOwnerId(ownerId)).willReturn(0L);
			given(serviceClientRepository.existsByClientName("중복이름앱")).willReturn(true);

			// when / then
			assertThatThrownBy(() -> service.selfRegister(command))
					.isInstanceOf(DuplicateClientNameException.class);
			then(sasClientRegistrar).should(never()).registerAuthorizationCodeClient(any(), any(), any());
		}

		@Test
		@DisplayName("clientName이 null이면 IllegalArgumentException 발생")
		void selfRegister_withNullClientName_throwsException() {
			// given — 입력값 검증이 DB 조회보다 앞에 있으므로 countByOwnerId stubbing 불필요
			Long ownerId = 4L;
			SelfRegisterOAuthClientCommand command =
					new SelfRegisterOAuthClientCommand(null, Set.of("https://ex.com/cb"), ownerId);

			// when / then
			assertThatThrownBy(() -> service.selfRegister(command))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
