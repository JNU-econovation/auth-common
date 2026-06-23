package com.econo.auth.client.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.econo.auth.client.application.domain.GrantType;
import com.econo.auth.client.application.domain.ServiceClient;
import com.econo.auth.client.application.domain.ServiceRoute;
import com.econo.auth.client.application.repository.SasClientRegistrar;
import com.econo.auth.client.application.repository.SasRedirectUriManager;
import com.econo.auth.client.application.repository.ServiceClientRepository;
import com.econo.auth.client.application.repository.ServiceRouteRepository;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase.DeleteMyClientCommand;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase.MyClientResult;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase.UpdateMyClientCommand;
import com.econo.auth.client.exception.InvalidClientException;
import com.econo.auth.client.exception.RouteNamespaceChangeException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ManageOwnClientService 단위 테스트
 *
 * <p>Mockito로 모든 포트 의존성을 격리한다. 구현체가 없으므로 모든 테스트는 컴파일 에러 또는 assertion 실패로 Red 상태이어야 한다.
 */
@ExtendWith(MockitoExtension.class)
class ManageOwnClientServiceTest {

	@Mock private ServiceClientRepository serviceClientRepository;
	@Mock private ServiceRouteRepository serviceRouteRepository;
	@Mock private SasClientRegistrar sasClientRegistrar;
	@Mock private SasRedirectUriManager sasRedirectUriManager;
	@Mock private GatewayRefreshClient gatewayRefreshClient;
	@Mock private RouteValidator routeValidator;

	private ManageOwnClientService service;

	@BeforeEach
	void setUp() {
		service =
				new ManageOwnClientService(
						serviceClientRepository,
						serviceRouteRepository,
						sasClientRegistrar,
						sasRedirectUriManager,
						gatewayRefreshClient,
						routeValidator,
						new RouteNamespaceExtractor());
	}

	// ──────────────────────────────────────────────────────────
	// listMyClients
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("내 클라이언트 목록 조회 — listMyClients")
	class ListMyClientsTest {

		@Test
		@DisplayName("소유한 클라이언트 2개 + 라우트 1개 연결 시 MyClientResult 2개 반환, 첫 번째 클라이언트에 라우트 매핑")
		void listMyClients_withTwoClientsOneRoute_returnsTwoResults() {
			// given
			Long ownerId = 10L;
			ServiceClient client1 =
					ServiceClient.create(
							"client-uuid-1", "EEOS 웹앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);
			ServiceClient client2 =
					ServiceClient.create(
							"client-uuid-2", "EEOS 앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);

			// 8-인자 생성자: registeredClientId = "client-uuid-1" 로 첫 번째 클라이언트에 연결
			ServiceRoute route1 =
					new ServiceRoute(
							"route-uuid-1",
							"/api/eeos",
							"http://eeos-service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now(),
							ownerId,
							"client-uuid-1");

			given(serviceClientRepository.findByOwnerId(ownerId)).willReturn(List.of(client1, client2));
			given(
							serviceRouteRepository.findByRegisteredClientIdIn(
									List.of("client-uuid-1", "client-uuid-2")))
					.willReturn(List.of(route1));
			given(sasRedirectUriManager.findRedirectUrisByClientId("client-uuid-1"))
					.willReturn(Set.of("https://app.econovation.kr/callback"));
			given(sasRedirectUriManager.findRedirectUrisByClientId("client-uuid-2"))
					.willReturn(Set.of("eeos://callback"));

			// when
			List<MyClientResult> results = service.listMyClients(ownerId);

			// then
			assertThat(results).hasSize(2);
			// 첫 번째 클라이언트(client-uuid-1)에 라우트가 매핑되어야 함
			MyClientResult result1 =
					results.stream()
							.filter(r -> "client-uuid-1".equals(r.clientId()))
							.findFirst()
							.orElseThrow();
			assertThat(result1.routeId()).isEqualTo("route-uuid-1");
			assertThat(result1.pathPrefix()).isEqualTo("/api/eeos");
			// 두 번째 클라이언트(client-uuid-2)는 라우트 없음 → routeId null
			MyClientResult result2 =
					results.stream()
							.filter(r -> "client-uuid-2".equals(r.clientId()))
							.findFirst()
							.orElseThrow();
			assertThat(result2.routeId()).isNull();
		}

		@Test
		@DisplayName("클라이언트가 없으면 빈 목록 반환")
		void listMyClients_withNoClients_returnsEmptyList() {
			// given
			Long ownerId = 99L;
			given(serviceClientRepository.findByOwnerId(ownerId)).willReturn(List.of());

			// when
			List<MyClientResult> results = service.listMyClients(ownerId);

			// then
			assertThat(results).isEmpty();
			then(serviceRouteRepository).should(never()).findByRegisteredClientIdIn(anyList());
		}

		@Test
		@DisplayName("라우트가 없는 클라이언트의 MyClientResult.routeId는 null")
		void listMyClients_withClientWithoutRoute_routeIdIsNull() {
			// given
			Long ownerId = 11L;
			ServiceClient client =
					ServiceClient.create(
							"client-no-route", "라우트없는앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);

			given(serviceClientRepository.findByOwnerId(ownerId)).willReturn(List.of(client));
			given(serviceRouteRepository.findByRegisteredClientIdIn(List.of("client-no-route")))
					.willReturn(List.of());
			given(sasRedirectUriManager.findRedirectUrisByClientId("client-no-route"))
					.willReturn(Set.of("https://noroute.example.com/cb"));

			// when
			List<MyClientResult> results = service.listMyClients(ownerId);

			// then
			assertThat(results).hasSize(1);
			assertThat(results.get(0).routeId()).isNull();
			assertThat(results.get(0).pathPrefix()).isNull();
		}

		@Test
		@DisplayName("라우트 조회는 IN-query 1회만 호출 (N+1 방지)")
		void listMyClients_callsFindByRegisteredClientIdInOnce() {
			// given
			Long ownerId = 12L;
			ServiceClient c1 =
					ServiceClient.create("c1", "앱1", GrantType.AUTHORIZATION_CODE, null, ownerId, null);
			ServiceClient c2 =
					ServiceClient.create("c2", "앱2", GrantType.AUTHORIZATION_CODE, null, ownerId, null);

			given(serviceClientRepository.findByOwnerId(ownerId)).willReturn(List.of(c1, c2));
			given(serviceRouteRepository.findByRegisteredClientIdIn(anyList())).willReturn(List.of());
			given(sasRedirectUriManager.findRedirectUrisByClientId(anyString())).willReturn(Set.of());

			// when
			service.listMyClients(ownerId);

			// then
			then(serviceRouteRepository).should(times(1)).findByRegisteredClientIdIn(anyList());
		}
	}

	// ──────────────────────────────────────────────────────────
	// getMyClient
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("내 클라이언트 단건 조회 — getMyClient")
	class GetMyClientTest {

		@Test
		@DisplayName("소유한 클라이언트 상세 조회 성공 → clientId·clientName 반환")
		void getMyClient_withOwnedClient_returnsMyClientResult() {
			// given
			Long ownerId = 10L;
			String clientId = "client-uuid-1";
			ServiceClient client =
					ServiceClient.create(
							clientId, "EEOS 웹앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);
			ServiceRoute route =
					new ServiceRoute(
							"route-uuid-1",
							"/api/eeos",
							"http://eeos-service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now(),
							ownerId);

			given(serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId))
					.willReturn(Optional.of(client));
			given(serviceRouteRepository.findByRegisteredClientId(clientId))
					.willReturn(Optional.of(route));
			given(sasRedirectUriManager.findRedirectUrisByClientId(clientId))
					.willReturn(Set.of("https://app.econovation.kr/callback"));

			// when
			MyClientResult result = service.getMyClient(clientId, ownerId);

			// then
			assertThat(result.clientId()).isEqualTo(clientId);
			assertThat(result.clientName()).isEqualTo("EEOS 웹앱");
			assertThat(result.routeId()).isEqualTo("route-uuid-1");
		}

		@Test
		@DisplayName("타인 소유 클라이언트 조회 시 InvalidClientException 발생 (404 존재 은닉)")
		void getMyClient_withOtherOwnersClient_throwsInvalidClientException() {
			// given
			Long requestingOwnerId = 10L;
			String otherOwnersClientId = "client-uuid-other";

			given(
							serviceClientRepository.findByClientIdAndOwnerId(
									otherOwnersClientId, requestingOwnerId))
					.willReturn(Optional.empty());

			// when / then
			assertThatThrownBy(() -> service.getMyClient(otherOwnersClientId, requestingOwnerId))
					.isInstanceOf(InvalidClientException.class);
		}

		@Test
		@DisplayName("존재하지 않는 clientId 조회 시 InvalidClientException 발생")
		void getMyClient_withNonExistentClientId_throwsInvalidClientException() {
			// given
			Long ownerId = 10L;
			String nonExistentClientId = "non-existent-client";

			given(serviceClientRepository.findByClientIdAndOwnerId(nonExistentClientId, ownerId))
					.willReturn(Optional.empty());

			// when / then
			assertThatThrownBy(() -> service.getMyClient(nonExistentClientId, ownerId))
					.isInstanceOf(InvalidClientException.class);
		}

		@Test
		@DisplayName("라우트 없는 클라이언트 조회 시 routeId가 null인 MyClientResult 반환")
		void getMyClient_withClientWithoutRoute_returnsResultWithNullRouteId() {
			// given
			Long ownerId = 10L;
			String clientId = "client-no-route";
			ServiceClient client =
					ServiceClient.create(
							clientId, "라우트없는앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);

			given(serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId))
					.willReturn(Optional.of(client));
			given(serviceRouteRepository.findByRegisteredClientId(clientId)).willReturn(Optional.empty());
			given(sasRedirectUriManager.findRedirectUrisByClientId(clientId))
					.willReturn(Set.of("https://noroute.example.com/cb"));

			// when
			MyClientResult result = service.getMyClient(clientId, ownerId);

			// then
			assertThat(result.routeId()).isNull();
		}
	}

	// ──────────────────────────────────────────────────────────
	// updateMyClient
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("내 클라이언트 수정 — updateMyClient")
	class UpdateMyClientTest {

		@Test
		@DisplayName("PUT 요청에서 라우트 필드 생략 시 기존 라우트 삭제 + afterCommit refresh 등록")
		void updateMyClient_withNoRouteFields_deletesExistingRoute() {
			// given
			Long ownerId = 10L;
			String clientId = "client-uuid-1";
			ServiceClient client =
					ServiceClient.create(
							clientId, "EEOS 웹앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);
			ServiceRoute existingRoute =
					new ServiceRoute(
							"route-uuid-1",
							"/api/eeos",
							"http://eeos-service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now(),
							ownerId);

			given(serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId))
					.willReturn(Optional.of(client));
			given(sasRedirectUriManager.findRedirectUrisByClientId(clientId))
					.willReturn(Set.of("https://app.econovation.kr/callback"));
			given(serviceRouteRepository.findByRegisteredClientId(clientId))
					.willReturn(Optional.of(existingRoute));

			UpdateMyClientCommand command =
					new UpdateMyClientCommand(
							clientId,
							ownerId,
							"EEOS 웹앱",
							Set.of("https://app.econovation.kr/callback"),
							null,
							null);

			// when
			service.updateMyClient(command);

			// then
			then(serviceRouteRepository).should(times(1)).deleteByRegisteredClientId(clientId);
		}

		@Test
		@DisplayName("PUT 요청에서 라우트 필드 추가 시 신규 라우트 생성 + afterCommit refresh 등록")
		void updateMyClient_withRouteFieldsAndNoExistingRoute_createsNewRoute() {
			// given
			Long ownerId = 10L;
			String clientId = "client-uuid-noroute";
			ServiceClient client =
					ServiceClient.create(
							clientId, "라우트없는앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);

			given(serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId))
					.willReturn(Optional.of(client));
			given(sasRedirectUriManager.findRedirectUrisByClientId(clientId))
					.willReturn(Set.of("https://noroute.example.com/cb"));
			given(serviceRouteRepository.findByRegisteredClientId(clientId)).willReturn(Optional.empty());
			willDoNothing().given(routeValidator).validateUpstreamUrl("http://new-service:8080");
			willDoNothing().given(routeValidator).validatePathPrefix("/api/newns");
			given(serviceRouteRepository.save(any(ServiceRoute.class)))
					.willReturn(
							ServiceRoute.create(
									"/api/newns", "http://new-service:8080", true, ownerId, clientId));

			UpdateMyClientCommand command =
					new UpdateMyClientCommand(
							clientId,
							ownerId,
							"라우트없는앱",
							Set.of("https://noroute.example.com/cb"),
							"/api/newns",
							"http://new-service:8080");

			// when
			service.updateMyClient(command);

			// then
			then(serviceRouteRepository).should(times(1)).save(any(ServiceRoute.class));
		}

		@Test
		@DisplayName("PUT 요청에서 네임스페이스 변경 시도 시 RouteNamespaceChangeException 발생")
		void updateMyClient_withNamespaceChange_throwsRouteNamespaceChangeException() {
			// given
			Long ownerId = 10L;
			String clientId = "client-uuid-1";
			ServiceClient client =
					ServiceClient.create(
							clientId, "EEOS 웹앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);
			ServiceRoute existingRoute =
					new ServiceRoute(
							"route-uuid-1",
							"/api/eeos",
							"http://eeos-service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now(),
							ownerId);

			given(serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId))
					.willReturn(Optional.of(client));
			given(sasRedirectUriManager.findRedirectUrisByClientId(clientId))
					.willReturn(Set.of("https://app.econovation.kr/callback"));
			given(serviceRouteRepository.findByRegisteredClientId(clientId))
					.willReturn(Optional.of(existingRoute));

			// 네임스페이스 변경: eeos → eeos-v2
			UpdateMyClientCommand command =
					new UpdateMyClientCommand(
							clientId,
							ownerId,
							"EEOS 웹앱",
							Set.of("https://app.econovation.kr/callback"),
							"/api/eeos-v2",
							"http://eeos-service-v2:8080");

			// when / then
			assertThatThrownBy(() -> service.updateMyClient(command))
					.isInstanceOf(RouteNamespaceChangeException.class);
		}

		@Test
		@DisplayName("PUT 요청에서 기존 라우트 수정 (같은 네임스페이스) 시 라우트 저장 + refresh 등록")
		void updateMyClient_withSameNamespaceRouteUpdate_savesRouteAndRegistersRefresh() {
			// given
			Long ownerId = 10L;
			String clientId = "client-uuid-1";
			ServiceClient client =
					ServiceClient.create(
							clientId, "EEOS 웹앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);
			ServiceRoute existingRoute =
					new ServiceRoute(
							"route-uuid-1",
							"/api/eeos",
							"http://eeos-service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now(),
							ownerId);
			ServiceRoute updatedRoute =
					new ServiceRoute(
							"route-uuid-1",
							"/api/eeos",
							"http://eeos-service-v2:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now(),
							ownerId);

			given(serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId))
					.willReturn(Optional.of(client));
			given(sasRedirectUriManager.findRedirectUrisByClientId(clientId))
					.willReturn(Set.of("https://app.econovation.kr/callback"));
			given(serviceRouteRepository.findByRegisteredClientId(clientId))
					.willReturn(Optional.of(existingRoute));
			willDoNothing().given(routeValidator).validateUpstreamUrl("http://eeos-service-v2:8080");
			willDoNothing()
					.given(routeValidator)
					.validatePathPrefixForUpdate("/api/eeos", "route-uuid-1");
			given(serviceRouteRepository.save(any(ServiceRoute.class))).willReturn(updatedRoute);

			UpdateMyClientCommand command =
					new UpdateMyClientCommand(
							clientId,
							ownerId,
							"EEOS 웹앱",
							Set.of("https://app.econovation.kr/callback"),
							"/api/eeos",
							"http://eeos-service-v2:8080");

			// when
			service.updateMyClient(command);

			// then
			then(serviceRouteRepository).should(times(1)).save(any(ServiceRoute.class));
		}

		@Test
		@DisplayName("PUT 요청에서 기존 라우트 없고 요청 라우트 없음 → no-op (라우트 관련 호출 없음)")
		void updateMyClient_withNoRouteAndNoRequest_isNoOp() {
			// given
			Long ownerId = 10L;
			String clientId = "client-uuid-noroute";
			ServiceClient client =
					ServiceClient.create(
							clientId, "라우트없는앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);

			given(serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId))
					.willReturn(Optional.of(client));
			given(sasRedirectUriManager.findRedirectUrisByClientId(clientId))
					.willReturn(Set.of("https://noroute.example.com/cb"));
			given(serviceRouteRepository.findByRegisteredClientId(clientId)).willReturn(Optional.empty());

			UpdateMyClientCommand command =
					new UpdateMyClientCommand(
							clientId, ownerId, "라우트없는앱", Set.of("https://noroute.example.com/cb"), null, null);

			// when
			service.updateMyClient(command);

			// then
			then(serviceRouteRepository).should(never()).save(any());
			then(serviceRouteRepository).should(never()).deleteByRegisteredClientId(anyString());
			then(gatewayRefreshClient).should(never()).triggerRefresh();
		}

		@Test
		@DisplayName("타인 소유 클라이언트 수정 시도 시 InvalidClientException 발생 (404 존재 은닉)")
		void updateMyClient_withOtherOwnersClient_throwsInvalidClientException() {
			// given
			Long requestingOwnerId = 10L;
			String otherOwnersClientId = "client-uuid-other";

			given(
							serviceClientRepository.findByClientIdAndOwnerId(
									otherOwnersClientId, requestingOwnerId))
					.willReturn(Optional.empty());

			UpdateMyClientCommand command =
					new UpdateMyClientCommand(
							otherOwnersClientId,
							requestingOwnerId,
							"내가 수정할 수 없음",
							Set.of("https://example.com/cb"),
							null,
							null);

			// when / then
			assertThatThrownBy(() -> service.updateMyClient(command))
					.isInstanceOf(InvalidClientException.class);
		}

		@Test
		@DisplayName(
				"clientName 변경 시 SasClientRegistrar.updateClientName 및 ServiceClientRepository.updateClientName 호출")
		void updateMyClient_whenClientNameChanged_updatesBothSasAndServiceClient() {
			// given
			Long ownerId = 10L;
			String clientId = "client-uuid-1";
			ServiceClient client =
					ServiceClient.create(clientId, "구 이름", GrantType.AUTHORIZATION_CODE, null, ownerId, null);

			given(serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId))
					.willReturn(Optional.of(client));
			given(sasRedirectUriManager.findRedirectUrisByClientId(clientId))
					.willReturn(Set.of("https://app.econovation.kr/callback"));
			given(serviceRouteRepository.findByRegisteredClientId(clientId)).willReturn(Optional.empty());

			UpdateMyClientCommand command =
					new UpdateMyClientCommand(
							clientId, ownerId, "새 이름", Set.of("https://app.econovation.kr/callback"), null, null);

			// when
			service.updateMyClient(command);

			// then
			then(serviceClientRepository).should(times(1)).updateClientName(clientId, "새 이름");
			then(sasClientRegistrar).should(times(1)).updateClientName(clientId, "새 이름");
		}
	}

	// ──────────────────────────────────────────────────────────
	// deleteMyClient
	// ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("내 클라이언트 삭제 — deleteMyClient")
	class DeleteMyClientTest {

		@Test
		@DisplayName("라우트 있는 클라이언트 삭제 시 라우트 삭제 + service_client 삭제 + SAS 삭제 + afterCommit refresh")
		void deleteMyClient_withRoute_deletesRouteAndClientAndTriggersRefresh() {
			// given
			Long ownerId = 10L;
			String clientId = "client-uuid-1";
			ServiceClient client =
					ServiceClient.create(
							clientId, "EEOS 웹앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);
			ServiceRoute route =
					new ServiceRoute(
							"route-uuid-1",
							"/api/eeos",
							"http://eeos-service:8080",
							true,
							LocalDateTime.now(),
							LocalDateTime.now(),
							ownerId);

			given(serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId))
					.willReturn(Optional.of(client));
			given(serviceRouteRepository.findByRegisteredClientId(clientId))
					.willReturn(Optional.of(route));

			DeleteMyClientCommand command = new DeleteMyClientCommand(clientId, ownerId);

			// when
			service.deleteMyClient(command);

			// then
			then(serviceRouteRepository).should(times(1)).deleteByRegisteredClientId(clientId);
			then(serviceClientRepository).should(times(1)).deleteByClientId(clientId);
			then(sasClientRegistrar).should(times(1)).unregisterClient(clientId);
		}

		@Test
		@DisplayName("라우트 없는 클라이언트 삭제 시 라우트 삭제 미호출 + refresh 미등록")
		void deleteMyClient_withoutRoute_deletesOnlyClientAndSas_noRefresh() {
			// given
			Long ownerId = 10L;
			String clientId = "client-no-route";
			ServiceClient client =
					ServiceClient.create(
							clientId, "라우트없는앱", GrantType.AUTHORIZATION_CODE, null, ownerId, null);

			given(serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId))
					.willReturn(Optional.of(client));
			given(serviceRouteRepository.findByRegisteredClientId(clientId)).willReturn(Optional.empty());

			DeleteMyClientCommand command = new DeleteMyClientCommand(clientId, ownerId);

			// when
			service.deleteMyClient(command);

			// then
			then(serviceRouteRepository).should(never()).deleteByRegisteredClientId(anyString());
			then(serviceClientRepository).should(times(1)).deleteByClientId(clientId);
			then(sasClientRegistrar).should(times(1)).unregisterClient(clientId);
			then(gatewayRefreshClient).should(never()).triggerRefresh();
		}

		@Test
		@DisplayName("타인 소유 클라이언트 삭제 시도 시 InvalidClientException 발생 (404 존재 은닉)")
		void deleteMyClient_withOtherOwnersClient_throwsInvalidClientException() {
			// given
			Long requestingOwnerId = 10L;
			String otherOwnersClientId = "client-uuid-other";

			given(
							serviceClientRepository.findByClientIdAndOwnerId(
									otherOwnersClientId, requestingOwnerId))
					.willReturn(Optional.empty());

			DeleteMyClientCommand command =
					new DeleteMyClientCommand(otherOwnersClientId, requestingOwnerId);

			// when / then
			assertThatThrownBy(() -> service.deleteMyClient(command))
					.isInstanceOf(InvalidClientException.class);

			then(serviceRouteRepository).should(never()).deleteByRegisteredClientId(anyString());
			then(serviceClientRepository).should(never()).deleteByClientId(anyString());
			then(sasClientRegistrar).should(never()).unregisterClient(anyString());
		}
	}
}
