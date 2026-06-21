package com.econo.auth.client.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.econo.auth.client.application.domain.ServiceRoute;
import com.econo.auth.client.application.repository.ServiceRouteRepository;
import com.econo.auth.client.application.usecase.ManageRouteUseCase.CreateRouteCommand;
import com.econo.auth.client.application.usecase.ManageRouteUseCase.RouteResult;
import com.econo.auth.client.application.usecase.ManageRouteUseCase.UpdateRouteCommand;
import com.econo.auth.client.exception.RouteNotFoundException;
import com.econo.auth.client.exception.RoutePathConflictException;
import com.econo.auth.client.exception.RouteProtectedException;
import com.econo.auth.client.exception.RouteUpstreamInvalidException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ManageRouteService 단위 테스트 */
@ExtendWith(MockitoExtension.class)
class ManageRouteServiceTest {

	@Mock private ServiceRouteRepository serviceRouteRepository;
	@Mock private GatewayRefreshClient gatewayRefreshClient;
	@Mock private RouteValidator routeValidator;

	private ManageRouteService service;

	@BeforeEach
	void setUp() {
		service = new ManageRouteService(serviceRouteRepository, gatewayRefreshClient, routeValidator);
	}

	// ──────────────────────────────────────────────────────────
	// 정상 create
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("라우트 등록 — 정상 경로")
	class CreateRouteSuccessTest {

		@Test
		@DisplayName("정상 등록 시 저장 후 RouteResult 반환")
		void createRoute_withValidInput_savesAndReturnsResult() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/new-service", "http://new-service:8080", true);
			ServiceRoute saved =
					new ServiceRoute(
							"route-uuid-1",
							"/api/v2/new-service",
							"http://new-service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			willDoNothing().given(routeValidator).validateUpstreamUrl("http://new-service:8080");
			willDoNothing().given(routeValidator).validatePathPrefix("/api/v2/new-service");
			given(serviceRouteRepository.save(any(ServiceRoute.class))).willReturn(saved);

			// when
			RouteResult result = service.createRoute(command);

			// then
			assertThat(result).isNotNull();
			assertThat(result.routeId()).isEqualTo("route-uuid-1");
			assertThat(result.pathPrefix()).isEqualTo("/api/v2/new-service");
			then(serviceRouteRepository).should(times(1)).save(any(ServiceRoute.class));
		}

		@Test
		@DisplayName("정상 등록 후 GatewayRefreshClient.triggerRefresh() 호출")
		void createRoute_afterSave_triggersGatewayRefresh() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/service", "http://service:8080", true);
			ServiceRoute saved =
					new ServiceRoute(
							"route-uuid-2",
							"/api/v2/service",
							"http://service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			willDoNothing().given(routeValidator).validateUpstreamUrl("http://service:8080");
			willDoNothing().given(routeValidator).validatePathPrefix("/api/v2/service");
			given(serviceRouteRepository.save(any(ServiceRoute.class))).willReturn(saved);

			// when
			service.createRoute(command);

			// then
			then(gatewayRefreshClient).should(times(1)).triggerRefresh();
		}

		@Test
		@DisplayName("https 스킴 허용")
		void createRoute_withHttpsScheme_succeeds() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/secure", "https://secure-service:8443", true);
			ServiceRoute saved =
					new ServiceRoute(
							"route-uuid-3",
							"/api/v2/secure",
							"https://secure-service:8443",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			willDoNothing().given(routeValidator).validateUpstreamUrl("https://secure-service:8443");
			willDoNothing().given(routeValidator).validatePathPrefix("/api/v2/secure");
			given(serviceRouteRepository.save(any(ServiceRoute.class))).willReturn(saved);

			// when / then — 예외 없이 성공
			RouteResult result = service.createRoute(command);
			assertThat(result.routeId()).isNotBlank();
		}
	}

	// ──────────────────────────────────────────────────────────
	// SSRF 검증
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("SSRF 검증 — 허용되지 않는 upstreamUrl")
	class SsrfValidationTest {

		@Test
		@DisplayName("file:// 스킴 사용 시 RouteUpstreamInvalidException 발생")
		void createRoute_withFileScheme_throwsRouteUpstreamInvalidException() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/malicious", "file:///etc/passwd", true);
			willThrow(new RouteUpstreamInvalidException("허용되지 않는 스킴"))
					.given(routeValidator)
					.validateUpstreamUrl("file:///etc/passwd");

			// when / then
			assertThatThrownBy(() -> service.createRoute(command))
					.isInstanceOf(RouteUpstreamInvalidException.class);
			then(serviceRouteRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("ftp:// 스킴 사용 시 RouteUpstreamInvalidException 발생")
		void createRoute_withFtpScheme_throwsRouteUpstreamInvalidException() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/ftp", "ftp://ftp.example.com", true);
			willThrow(new RouteUpstreamInvalidException("허용되지 않는 스킴"))
					.given(routeValidator)
					.validateUpstreamUrl("ftp://ftp.example.com");

			// when / then
			assertThatThrownBy(() -> service.createRoute(command))
					.isInstanceOf(RouteUpstreamInvalidException.class);
		}

		@Test
		@DisplayName("loopback 주소(127.0.0.1) 사용 시 RouteUpstreamInvalidException 발생")
		void createRoute_withLoopbackIp_throwsRouteUpstreamInvalidException() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/local", "http://127.0.0.1:8080", true);
			willThrow(new RouteUpstreamInvalidException("private IP 차단"))
					.given(routeValidator)
					.validateUpstreamUrl("http://127.0.0.1:8080");

			// when / then
			assertThatThrownBy(() -> service.createRoute(command))
					.isInstanceOf(RouteUpstreamInvalidException.class);
		}

		@Test
		@DisplayName("private IP(10.x.x.x) 사용 시 RouteUpstreamInvalidException 발생")
		void createRoute_withPrivateIp10Block_throwsRouteUpstreamInvalidException() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/private", "http://10.0.0.5:8080", true);
			willThrow(new RouteUpstreamInvalidException("private IP 차단"))
					.given(routeValidator)
					.validateUpstreamUrl("http://10.0.0.5:8080");

			// when / then
			assertThatThrownBy(() -> service.createRoute(command))
					.isInstanceOf(RouteUpstreamInvalidException.class);
		}

		@Test
		@DisplayName("private IP(192.168.x.x) 사용 시 RouteUpstreamInvalidException 발생")
		void createRoute_withPrivateIp192Block_throwsRouteUpstreamInvalidException() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/private2", "http://192.168.1.100:8080", true);
			willThrow(new RouteUpstreamInvalidException("private IP 차단"))
					.given(routeValidator)
					.validateUpstreamUrl("http://192.168.1.100:8080");

			// when / then
			assertThatThrownBy(() -> service.createRoute(command))
					.isInstanceOf(RouteUpstreamInvalidException.class);
		}

		@Test
		@DisplayName("private IP(172.16.x.x) 사용 시 RouteUpstreamInvalidException 발생")
		void createRoute_withPrivateIp172Block_throwsRouteUpstreamInvalidException() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/private3", "http://172.16.0.1:8080", true);
			willThrow(new RouteUpstreamInvalidException("private IP 차단"))
					.given(routeValidator)
					.validateUpstreamUrl("http://172.16.0.1:8080");

			// when / then
			assertThatThrownBy(() -> service.createRoute(command))
					.isInstanceOf(RouteUpstreamInvalidException.class);
		}

		@Test
		@DisplayName("localhost 호스트명 사용 시 RouteUpstreamInvalidException 발생")
		void createRoute_withLocalhostHostname_throwsRouteUpstreamInvalidException() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/localhost", "http://localhost:8080", true);
			willThrow(new RouteUpstreamInvalidException("localhost는 허용되지 않습니다."))
					.given(routeValidator)
					.validateUpstreamUrl("http://localhost:8080");

			// when / then
			assertThatThrownBy(() -> service.createRoute(command))
					.isInstanceOf(RouteUpstreamInvalidException.class);
		}

		@Test
		@DisplayName("와일드카드 주소(0.0.0.0) 사용 시 RouteUpstreamInvalidException 발생")
		void createRoute_withAnyLocalAddress_throwsRouteUpstreamInvalidException() {
			// given — 0.0.0.0은 isAnyLocalAddress()=true
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/any-local", "http://0.0.0.0:8080", true);
			willThrow(new RouteUpstreamInvalidException("private IP 차단"))
					.given(routeValidator)
					.validateUpstreamUrl("http://0.0.0.0:8080");

			// when / then
			assertThatThrownBy(() -> service.createRoute(command))
					.isInstanceOf(RouteUpstreamInvalidException.class);
		}

		@Test
		@DisplayName("SSRF 검증 실패 시 repository.save 미호출")
		void createRoute_ssrfFailure_doesNotCallSave() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/ssrf", "http://192.168.0.1:8080", true);
			willThrow(new RouteUpstreamInvalidException("private IP 차단"))
					.given(routeValidator)
					.validateUpstreamUrl("http://192.168.0.1:8080");

			// when
			try {
				service.createRoute(command);
			} catch (RouteUpstreamInvalidException e) {
				// expected
			}

			// then
			then(serviceRouteRepository).should(never()).save(any());
			then(gatewayRefreshClient).should(never()).triggerRefresh();
		}
	}

	// ──────────────────────────────────────────────────────────
	// pathPrefix 중복
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("pathPrefix 중복 검증")
	class PathPrefixConflictTest {

		@Test
		@DisplayName("이미 존재하는 pathPrefix 등록 시 RoutePathConflictException 발생")
		void createRoute_withDuplicatePathPrefix_throwsRoutePathConflictException() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v2/existing", "http://service:8080", true);
			willDoNothing().given(routeValidator).validateUpstreamUrl("http://service:8080");
			willThrow(new RoutePathConflictException("/api/v2/existing"))
					.given(routeValidator)
					.validatePathPrefix("/api/v2/existing");

			// when / then
			assertThatThrownBy(() -> service.createRoute(command))
					.isInstanceOf(RoutePathConflictException.class);
			then(serviceRouteRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("수정 시 다른 라우트와 pathPrefix 충돌이면 RoutePathConflictException 발생")
		void updateRoute_withConflictingPathPrefix_throwsRoutePathConflictException() {
			// given
			String routeId = "route-uuid-existing";
			UpdateRouteCommand command =
					new UpdateRouteCommand("/api/v2/conflict", "http://service:8080", true);
			ServiceRoute existing =
					new ServiceRoute(
							routeId,
							"/api/v2/old",
							"http://old-service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			given(serviceRouteRepository.findById(routeId)).willReturn(Optional.of(existing));
			given(routeValidator.isProtected("/api/v2/old")).willReturn(false);
			willDoNothing().given(routeValidator).validateUpstreamUrl("http://service:8080");
			willThrow(new RoutePathConflictException("/api/v2/conflict"))
					.given(routeValidator)
					.validatePathPrefixForUpdate("/api/v2/conflict", routeId);

			// when / then
			assertThatThrownBy(() -> service.updateRoute(routeId, command))
					.isInstanceOf(RoutePathConflictException.class);
		}

		@Test
		@DisplayName("수정 시 자신의 pathPrefix와 동일하면 충돌로 처리하지 않음")
		void updateRoute_withSamePathPrefix_doesNotThrowConflict() {
			// given
			String routeId = "route-uuid-self";
			UpdateRouteCommand command =
					new UpdateRouteCommand("/api/v2/self", "http://new-service:8080", true);
			ServiceRoute existing =
					new ServiceRoute(
							routeId,
							"/api/v2/self",
							"http://old-service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			ServiceRoute updated =
					new ServiceRoute(
							routeId,
							"/api/v2/self",
							"http://new-service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			given(serviceRouteRepository.findById(routeId)).willReturn(Optional.of(existing));
			given(routeValidator.isProtected("/api/v2/self")).willReturn(false);
			willDoNothing().given(routeValidator).validateUpstreamUrl("http://new-service:8080");
			willDoNothing().given(routeValidator).validatePathPrefixForUpdate("/api/v2/self", routeId);
			given(serviceRouteRepository.save(any(ServiceRoute.class))).willReturn(updated);

			// when / then — 예외 없이 성공
			RouteResult result = service.updateRoute(routeId, command);
			assertThat(result.routeId()).isEqualTo(routeId);
		}
	}

	// ──────────────────────────────────────────────────────────
	// 보호 경로 가로채기/삭제 금지
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("보호 경로 가로채기 및 삭제 금지")
	class ProtectedPathTest {

		@Test
		@DisplayName("보호 경로(/api/v1/auth/**) 등록 시도 시 RouteProtectedException 발생")
		void createRoute_withProtectedAuthPath_throwsRouteProtectedException() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/api/v1/auth/hijack", "http://evil:9090", true);
			willDoNothing().given(routeValidator).validateUpstreamUrl("http://evil:9090");
			willThrow(new RouteProtectedException("/api/v1/auth/hijack"))
					.given(routeValidator)
					.validatePathPrefix("/api/v1/auth/hijack");

			// when / then
			assertThatThrownBy(() -> service.createRoute(command))
					.isInstanceOf(RouteProtectedException.class);
			then(serviceRouteRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("보호 경로(/oauth2/**) 등록 시도 시 RouteProtectedException 발생")
		void createRoute_withProtectedOauth2Path_throwsRouteProtectedException() {
			// given
			CreateRouteCommand command =
					new CreateRouteCommand("/oauth2/token", "http://evil:9090", true);
			willDoNothing().given(routeValidator).validateUpstreamUrl("http://evil:9090");
			willThrow(new RouteProtectedException("/oauth2/token"))
					.given(routeValidator)
					.validatePathPrefix("/oauth2/token");

			// when / then
			assertThatThrownBy(() -> service.createRoute(command))
					.isInstanceOf(RouteProtectedException.class);
		}

		@Test
		@DisplayName("보호 경로로 변경 시도 시 RouteProtectedException 발생")
		void updateRoute_withProtectedPathPrefix_throwsRouteProtectedException() {
			// given
			String routeId = "route-uuid-protected";
			UpdateRouteCommand command =
					new UpdateRouteCommand("/api/v1/admin/routes", "http://evil:9090", true);
			ServiceRoute existing =
					new ServiceRoute(
							routeId,
							"/api/v2/old",
							"http://service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			given(serviceRouteRepository.findById(routeId)).willReturn(Optional.of(existing));
			given(routeValidator.isProtected("/api/v2/old")).willReturn(false);
			willDoNothing().given(routeValidator).validateUpstreamUrl("http://evil:9090");
			willThrow(new RouteProtectedException("/api/v1/admin/routes"))
					.given(routeValidator)
					.validatePathPrefixForUpdate("/api/v1/admin/routes", routeId);

			// when / then
			assertThatThrownBy(() -> service.updateRoute(routeId, command))
					.isInstanceOf(RouteProtectedException.class);
		}

		@Test
		@DisplayName("기존 라우트의 pathPrefix가 보호경로이면 upstream 변조 시도 차단 (RouteProtectedException)")
		void updateRoute_withExistingProtectedPath_throwsRouteProtectedException() {
			// given — 기존 라우트 자체가 보호경로 (예: 시드 등록 후 변조 시도)
			String routeId = "route-uuid-existing-protected";
			UpdateRouteCommand command = new UpdateRouteCommand("/api/v2/new", "http://evil:9090", true);
			ServiceRoute existingProtected =
					new ServiceRoute(
							routeId,
							"/api/v1/auth/login",
							"http://auth-api:8081",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			given(serviceRouteRepository.findById(routeId)).willReturn(Optional.of(existingProtected));
			given(routeValidator.isProtected("/api/v1/auth/login")).willReturn(true);

			// when / then
			assertThatThrownBy(() -> service.updateRoute(routeId, command))
					.isInstanceOf(RouteProtectedException.class);
			then(serviceRouteRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("보호 경로로 등록된 라우트 삭제 시도 시 RouteProtectedException 발생")
		void deleteRoute_withProtectedPathRoute_throwsRouteProtectedException() {
			// given
			String routeId = "route-uuid-auth-protected";
			ServiceRoute protectedRoute =
					new ServiceRoute(
							routeId,
							"/api/v1/auth/login",
							"http://auth-api:8081",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			given(serviceRouteRepository.findById(routeId)).willReturn(Optional.of(protectedRoute));
			given(routeValidator.isProtected("/api/v1/auth/login")).willReturn(true);

			// when / then
			assertThatThrownBy(() -> service.deleteRoute(routeId))
					.isInstanceOf(RouteProtectedException.class);
			then(serviceRouteRepository).should(never()).deleteById(anyString());
		}
	}

	// ──────────────────────────────────────────────────────────
	// 정상 update
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("라우트 수정 — 정상 경로")
	class UpdateRouteSuccessTest {

		@Test
		@DisplayName("정상 수정 시 저장 후 RouteResult 반환")
		void updateRoute_withValidInput_savesAndReturnsResult() {
			// given
			String routeId = "route-uuid-update";
			UpdateRouteCommand command =
					new UpdateRouteCommand("/api/v2/renamed", "http://renamed:8080", false);
			ServiceRoute existing =
					new ServiceRoute(
							routeId,
							"/api/v2/old",
							"http://old:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			ServiceRoute updated =
					new ServiceRoute(
							routeId,
							"/api/v2/renamed",
							"http://renamed:8080",
							false,
							LocalDateTime.now(),
							LocalDateTime.now());
			given(serviceRouteRepository.findById(routeId)).willReturn(Optional.of(existing));
			given(routeValidator.isProtected("/api/v2/old")).willReturn(false);
			willDoNothing().given(routeValidator).validateUpstreamUrl("http://renamed:8080");
			willDoNothing().given(routeValidator).validatePathPrefixForUpdate("/api/v2/renamed", routeId);
			given(serviceRouteRepository.save(any(ServiceRoute.class))).willReturn(updated);

			// when
			RouteResult result = service.updateRoute(routeId, command);

			// then
			assertThat(result.pathPrefix()).isEqualTo("/api/v2/renamed");
			assertThat(result.enabled()).isFalse();
			then(gatewayRefreshClient).should(times(1)).triggerRefresh();
		}

		@Test
		@DisplayName("존재하지 않는 routeId 수정 시 RouteNotFoundException 발생")
		void updateRoute_withNonExistentRouteId_throwsRouteNotFoundException() {
			// given
			given(serviceRouteRepository.findById("non-existent")).willReturn(Optional.empty());

			// when / then
			assertThatThrownBy(
							() ->
									service.updateRoute(
											"non-existent", new UpdateRouteCommand("/api/v2/x", "http://x:8080", true)))
					.isInstanceOf(RouteNotFoundException.class);
		}
	}

	// ──────────────────────────────────────────────────────────
	// 정상 delete
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("라우트 삭제 — 정상 경로")
	class DeleteRouteSuccessTest {

		@Test
		@DisplayName("정상 삭제 후 GatewayRefreshClient 호출")
		void deleteRoute_withValidRouteId_deletesAndTriggersRefresh() {
			// given
			String routeId = "route-uuid-delete";
			ServiceRoute route =
					new ServiceRoute(
							routeId,
							"/api/v2/to-delete",
							"http://delete-me:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			given(serviceRouteRepository.findById(routeId)).willReturn(Optional.of(route));
			given(routeValidator.isProtected("/api/v2/to-delete")).willReturn(false);

			// when
			service.deleteRoute(routeId);

			// then
			then(serviceRouteRepository).should(times(1)).deleteById(routeId);
			then(gatewayRefreshClient).should(times(1)).triggerRefresh();
		}

		@Test
		@DisplayName("존재하지 않는 routeId 삭제 시 RouteNotFoundException 발생")
		void deleteRoute_withNonExistentRouteId_throwsRouteNotFoundException() {
			// given
			given(serviceRouteRepository.findById("not-exist")).willReturn(Optional.empty());

			// when / then
			assertThatThrownBy(() -> service.deleteRoute("not-exist"))
					.isInstanceOf(RouteNotFoundException.class);
			then(serviceRouteRepository).should(never()).deleteById(anyString());
		}
	}

	// ──────────────────────────────────────────────────────────
	// listRoutes / getRoute
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("라우트 목록 조회 및 단건 조회")
	class ListAndGetRouteTest {

		@Test
		@DisplayName("listRoutes() — 전체 목록 반환")
		void listRoutes_returnsAllRoutes() {
			// given
			List<ServiceRoute> routes =
					List.of(
							new ServiceRoute(
									"r1",
									"/api/v2/a",
									"http://a:8080",
									true,
									LocalDateTime.now(),
									LocalDateTime.now()),
							new ServiceRoute(
									"r2",
									"/api/v2/b",
									"http://b:8080",
									false,
									LocalDateTime.now(),
									LocalDateTime.now()));
			given(serviceRouteRepository.findAll()).willReturn(routes);

			// when
			List<RouteResult> result = service.listRoutes();

			// then
			assertThat(result).hasSize(2);
			assertThat(result.get(0).routeId()).isEqualTo("r1");
			assertThat(result.get(1).routeId()).isEqualTo("r2");
		}

		@Test
		@DisplayName("getRoute() — 존재하는 routeId 단건 반환")
		void getRoute_withExistingId_returnsRouteResult() {
			// given
			String routeId = "r-get-1";
			ServiceRoute route =
					new ServiceRoute(
							routeId,
							"/api/v2/target",
							"http://target:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now());
			given(serviceRouteRepository.findById(routeId)).willReturn(Optional.of(route));

			// when
			RouteResult result = service.getRoute(routeId);

			// then
			assertThat(result.routeId()).isEqualTo(routeId);
			assertThat(result.pathPrefix()).isEqualTo("/api/v2/target");
		}

		@Test
		@DisplayName("getRoute() — 존재하지 않는 routeId 시 RouteNotFoundException 발생")
		void getRoute_withNonExistentId_throwsRouteNotFoundException() {
			// given
			given(serviceRouteRepository.findById("missing")).willReturn(Optional.empty());

			// when / then
			assertThatThrownBy(() -> service.getRoute("missing"))
					.isInstanceOf(RouteNotFoundException.class);
		}
	}
}
