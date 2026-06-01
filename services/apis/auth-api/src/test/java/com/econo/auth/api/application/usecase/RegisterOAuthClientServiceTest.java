package com.econo.auth.api.application.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import com.econo.auth.api.application.port.out.SasClientRegistrar;
import com.econo.auth.api.application.port.out.ServiceClientRepository;
import com.econo.auth.api.application.port.out.ServiceRouteRepository;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult;
import com.econo.auth.api.domain.GrantType;
import com.econo.auth.api.domain.ServiceClient;
import com.econo.auth.api.domain.ServiceRoute;
import com.econo.auth.api.exception.DuplicateClientNameException;
import com.econo.auth.api.exception.RedirectUriRequiredException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * RegisterOAuthClientService 단위 테스트
 *
 * <p>SAS 인프라 의존은 {@link SasClientRegistrar} 포트 뒤로 격리되어 있으므로 실제 SAS 없이 Mockito로 테스트한다.
 */
@ExtendWith(MockitoExtension.class)
class RegisterOAuthClientServiceTest {

	@Mock private SasClientRegistrar sasClientRegistrar;
	@Mock private ServiceClientRepository serviceClientRepository;
	@Mock private ServiceRouteRepository serviceRouteRepository;

	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
	private RegisterOAuthClientService service;

	@BeforeEach
	void setUp() {
		service =
				new RegisterOAuthClientService(
						sasClientRegistrar, serviceClientRepository, serviceRouteRepository, passwordEncoder);
	}

	// ──────────────────────────────────────────────────────────
	// authorization_code 그랜트
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("authorization_code 그랜트 타입 등록")
	class AuthorizationCodeGrantTest {

		@Test
		@DisplayName("authorization_code 등록 성공 시 SAS 등록 + ServiceClient 저장이 호출된다")
		void registerAuthorizationCode_savesBothRepositories() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(
							GrantType.AUTHORIZATION_CODE,
							"테스트 SPA",
							Set.of("http://localhost:3000/callback"),
							null,
							null);

			RegisterOAuthClientResult result = service.register(command);

			then(sasClientRegistrar)
					.should(times(1))
					.registerAuthorizationCodeClient(anyString(), eq("테스트 SPA"), any());
			then(serviceClientRepository).should(times(1)).save(any(ServiceClient.class));
			then(serviceRouteRepository).should(never()).save(any(ServiceRoute.class));
			assertThat(result.clientId()).isNotBlank();
			assertThat(result.clientSecret()).isNull();
		}

		@Test
		@DisplayName("authorization_code 등록 시 redirectUris가 없으면 RedirectUriRequiredException 발생")
		void registerAuthorizationCode_withoutRedirectUris_throwsException() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(GrantType.AUTHORIZATION_CODE, "테스트 SPA", null, null, null);

			assertThatThrownBy(() -> service.register(command))
					.isInstanceOf(RedirectUriRequiredException.class);

			then(sasClientRegistrar).should(never()).registerAuthorizationCodeClient(any(), any(), any());
			then(serviceClientRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("authorization_code 등록 시 redirectUris가 비어있으면 RedirectUriRequiredException 발생")
		void registerAuthorizationCode_withEmptyRedirectUris_throwsException() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(
							GrantType.AUTHORIZATION_CODE, "테스트 SPA", Set.of(), null, null);

			assertThatThrownBy(() -> service.register(command))
					.isInstanceOf(RedirectUriRequiredException.class);
		}
	}

	// ──────────────────────────────────────────────────────────
	// client_credentials 그랜트
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("client_credentials 그랜트 타입 등록")
	class ClientCredentialsGrantTest {

		@Test
		@DisplayName("client_credentials 등록 성공 시 rawSecret이 1회 반환된다")
		void registerClientCredentials_returnsRawSecretOnce() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(GrantType.CLIENT_CREDENTIALS, "배치 서비스", null, null, null);

			RegisterOAuthClientResult result = service.register(command);

			assertThat(result.clientId()).isNotBlank();
			assertThat(result.clientSecret()).isNotBlank();
		}

		@Test
		@DisplayName("client_credentials 등록 시 SAS에 BCrypt 해시된 secret이 전달된다")
		void registerClientCredentials_savesBCryptHashedSecretToSas() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(GrantType.CLIENT_CREDENTIALS, "배치 서비스", null, null, null);

			service.register(command);

			// SAS 포트에 전달된 secret이 {bcrypt} 형식인지 검증
			then(sasClientRegistrar)
					.should(times(1))
					.registerClientCredentialsClient(
							anyString(),
							eq("배치 서비스"),
							argThat(secret -> secret != null && secret.startsWith("{bcrypt}")));
		}

		@Test
		@DisplayName("client_credentials 등록 시 ServiceClientRepository에도 저장된다")
		void registerClientCredentials_savesSha256HashToServiceClient() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(GrantType.CLIENT_CREDENTIALS, "배치 서비스", null, null, null);

			service.register(command);

			then(serviceClientRepository).should(times(1)).save(any(ServiceClient.class));
		}

		@Test
		@DisplayName("두 번 호출해도 서로 다른 secret이 반환된다 (매번 새로 생성)")
		void registerClientCredentials_generatesDifferentSecretEachTime() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(
							GrantType.CLIENT_CREDENTIALS, "배치 서비스 A", null, null, null);
			RegisterOAuthClientCommand command2 =
					new RegisterOAuthClientCommand(
							GrantType.CLIENT_CREDENTIALS, "배치 서비스 B", null, null, null);

			RegisterOAuthClientResult result1 = service.register(command);
			RegisterOAuthClientResult result2 = service.register(command2);

			assertThat(result1.clientSecret()).isNotEqualTo(result2.clientSecret());
		}
	}

	// ──────────────────────────────────────────────────────────
	// upstreamUrl 라우트 등록
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("upstreamUrl + pathPrefix 지정 시 라우트 등록")
	class RouteRegistrationTest {

		@Test
		@DisplayName("upstreamUrl이 있으면 ServiceRouteRepository.save()가 호출된다")
		void registerWithUpstreamUrl_savesRoute() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(
							GrantType.CLIENT_CREDENTIALS,
							"이커머스 서비스",
							null,
							"http://ecommerce-service:8080",
							"/api/shop");

			RegisterOAuthClientResult result = service.register(command);

			then(serviceRouteRepository).should(times(1)).save(any(ServiceRoute.class));
			assertThat(result.routeId()).isNotBlank();
		}

		@Test
		@DisplayName("upstreamUrl이 없으면 ServiceRouteRepository.save()가 호출되지 않는다")
		void registerWithoutUpstreamUrl_doesNotSaveRoute() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(GrantType.CLIENT_CREDENTIALS, "배치 서비스", null, null, null);

			service.register(command);

			then(serviceRouteRepository).should(never()).save(any(ServiceRoute.class));
		}

		@Test
		@DisplayName("upstreamUrl만 있고 pathPrefix가 없으면 routeId가 반환된다")
		void registerWithUpstreamUrlOnly_returnsRouteId() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(
							GrantType.CLIENT_CREDENTIALS,
							"이커머스 서비스",
							null,
							"http://ecommerce-service:8080",
							null);

			RegisterOAuthClientResult result = service.register(command);

			then(serviceRouteRepository).should(times(1)).save(any(ServiceRoute.class));
			assertThat(result.routeId()).isNotBlank();
		}
	}

	// ──────────────────────────────────────────────────────────
	// 중복 등록 방지
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("중복 클라이언트 이름 등록 방지")
	class DuplicateClientTest {

		@Test
		@DisplayName("이미 존재하는 clientName으로 등록 시 DuplicateClientNameException 발생")
		void registerWithDuplicateClientName_throwsException() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(GrantType.CLIENT_CREDENTIALS, "이미있는서비스", null, null, null);
			given(serviceClientRepository.existsByClientName("이미있는서비스")).willReturn(true);

			assertThatThrownBy(() -> service.register(command))
					.isInstanceOf(DuplicateClientNameException.class);

			then(sasClientRegistrar).should(never()).registerAuthorizationCodeClient(any(), any(), any());
			then(sasClientRegistrar).should(never()).registerClientCredentialsClient(any(), any(), any());
		}
	}

	// ──────────────────────────────────────────────────────────
	// 입력 검증
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("입력값 검증")
	class ValidationTest {

		@Test
		@DisplayName("clientName이 null이면 IllegalArgumentException 발생")
		void registerWithNullClientName_throwsException() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(GrantType.CLIENT_CREDENTIALS, null, null, null, null);

			assertThatThrownBy(() -> service.register(command))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("grantType이 null이면 IllegalArgumentException 발생")
		void registerWithNullGrantType_throwsException() {
			RegisterOAuthClientCommand command =
					new RegisterOAuthClientCommand(null, "서비스이름", null, null, null);

			assertThatThrownBy(() -> service.register(command))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	// ──────────────────────────────────────────────────────────
	// 라우트 목록 조회
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("라우트 목록 조회")
	class GetRoutesTest {

		@Test
		@DisplayName("등록된 라우트가 있으면 목록을 반환한다")
		void getRoutes_returnsRegisteredRoutes() {
			ServiceRoute route =
					new ServiceRoute(
							"route-uuid-001", "client-uuid-123", "http://ecommerce:8080", "/api/shop");
			given(serviceRouteRepository.findAll()).willReturn(List.of(route));

			List<ServiceRoute> routes = service.getRoutes();

			assertThat(routes).hasSize(1);
			assertThat(routes.get(0).routeId()).isEqualTo("route-uuid-001");
		}

		@Test
		@DisplayName("등록된 라우트가 없으면 빈 목록을 반환한다")
		void getRoutes_withNoRoutes_returnsEmptyList() {
			given(serviceRouteRepository.findAll()).willReturn(List.of());

			List<ServiceRoute> routes = service.getRoutes();

			assertThat(routes).isEmpty();
		}
	}
}
