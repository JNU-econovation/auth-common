package com.econo.auth.client.application.service;

import com.econo.auth.client.application.domain.ServiceClient;
import com.econo.auth.client.application.domain.ServiceRoute;
import com.econo.auth.client.application.repository.SasClientRegistrar;
import com.econo.auth.client.application.repository.SasRedirectUriManager;
import com.econo.auth.client.application.repository.ServiceClientRepository;
import com.econo.auth.client.application.repository.ServiceRouteRepository;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase;
import com.econo.auth.client.exception.InvalidClientException;
import com.econo.auth.client.exception.RouteNamespaceChangeException;
import com.econo.auth.client.exception.RouteNamespaceTakenException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 셀프 클라이언트 관리 유스케이스 구현체
 *
 * <p>인증된 회원이 자기 소유 OAuth 클라이언트(+연결 라우트)를 직접 조회·수정·삭제한다. 소유권 검증·diff 처리·SAS 동기화·게이트웨이 refresh
 * afterCommit을 단일 {@code @Transactional} 경계 안에서 조율한다.
 *
 * <p>ApplicationServiceConfig에서 @Bean 등록 (GatewayRefreshClient가 auth-api 소속 빈이어서 service-client 스캔
 * 범위 밖 — service-client 내부 @Service 사용 안 함).
 */
@Slf4j
@RequiredArgsConstructor
public class ManageOwnClientService implements ManageOwnClientUseCase {

	private final ServiceClientRepository serviceClientRepository;
	private final ServiceRouteRepository serviceRouteRepository;
	private final SasClientRegistrar sasClientRegistrar;
	private final SasRedirectUriManager sasRedirectUriManager;
	private final GatewayRefreshClient gatewayRefreshClient;
	private final RouteValidator routeValidator;
	private final RouteNamespaceExtractor namespaceExtractor;

	/**
	 * 내 클라이언트 목록 조회
	 *
	 * <p>{@code serviceClientRepository.findByOwnerId} → clientId 모아 {@code
	 * serviceRouteRepository.findByRegisteredClientIdIn}으로 IN-query 1회(N+1 방지) → 매핑
	 *
	 * @param ownerId 조회 대상 회원 ID
	 * @return 클라이언트 목록 (빈 목록 가능)
	 */
	@Override
	@Transactional(readOnly = true)
	public List<MyClientResult> listMyClients(Long ownerId) {
		List<ServiceClient> clients = serviceClientRepository.findByOwnerId(ownerId);
		if (clients.isEmpty()) {
			return List.of();
		}

		List<String> clientIds = clients.stream().map(ServiceClient::getRegisteredClientId).toList();

		// IN-query 1회 — N+1 방지
		List<ServiceRoute> routes = serviceRouteRepository.findByRegisteredClientIdIn(clientIds);

		return clients.stream()
				.map(
						client -> {
							String clientId = client.getRegisteredClientId();
							ServiceRoute route =
									routes.stream()
											.filter(r -> clientId.equals(r.registeredClientId()))
											.findFirst()
											.orElse(null);
							Set<String> redirectUris = sasRedirectUriManager.findRedirectUrisByClientId(clientId);
							return toResult(client, route, redirectUris);
						})
				.toList();
	}

	/**
	 * 내 클라이언트 단건 상세 조회
	 *
	 * @param clientId 조회 대상 클라이언트 ID
	 * @param ownerId 요청자 회원 ID (소유권 검증용)
	 * @return 클라이언트 상세 정보
	 * @throws InvalidClientException 미존재 또는 타인 소유 시 (404 존재 은닉)
	 */
	@Override
	@Transactional(readOnly = true)
	public MyClientResult getMyClient(String clientId, Long ownerId) {
		ServiceClient client =
				serviceClientRepository
						.findByClientIdAndOwnerId(clientId, ownerId)
						.orElseThrow(InvalidClientException::new);

		Optional<ServiceRoute> routeOpt = serviceRouteRepository.findByRegisteredClientId(clientId);
		Set<String> redirectUris = sasRedirectUriManager.findRedirectUrisByClientId(clientId);

		return toResult(client, routeOpt.orElse(null), redirectUris);
	}

	/**
	 * 내 클라이언트 수정 (전체 표현 교체)
	 *
	 * @param command 수정 명령
	 * @return 수정 후 상태
	 * @throws InvalidClientException 미존재 또는 타인 소유 시 (404 존재 은닉)
	 * @throws RouteNamespaceChangeException 네임스페이스 변경 시도 시 (400)
	 */
	@Override
	@Transactional
	public MyClientResult updateMyClient(UpdateMyClientCommand command) {
		// [1단계] 소유권 검증
		ServiceClient existing =
				serviceClientRepository
						.findByClientIdAndOwnerId(command.clientId(), command.ownerId())
						.orElseThrow(InvalidClientException::new);

		// [2단계] clientName diff
		if (!existing.getClientName().equals(command.clientName())) {
			serviceClientRepository.updateClientName(command.clientId(), command.clientName());
			sasClientRegistrar.updateClientName(command.clientId(), command.clientName());
		}

		// [3단계] redirectUris diff
		Set<String> currentUris = sasRedirectUriManager.findRedirectUrisByClientId(command.clientId());
		if (currentUris != null && !currentUris.equals(command.redirectUris())) {
			sasRedirectUriManager.updateRedirectUris(command.clientId(), command.redirectUris());
		} else if (currentUris == null) {
			sasRedirectUriManager.updateRedirectUris(command.clientId(), command.redirectUris());
		}

		// [4단계] 라우트 diff (4가지 케이스)
		Optional<ServiceRoute> existingRouteOpt =
				serviceRouteRepository.findByRegisteredClientId(command.clientId());

		boolean hasRequestRoute =
				command.pathPrefix() != null
						&& !command.pathPrefix().isBlank()
						&& command.upstreamUrl() != null
						&& !command.upstreamUrl().isBlank();

		// 케이스 분기에서 finalRoute를 확정 — 이중 판별 없이 한 곳에서 결정
		final ServiceRoute finalRoute;

		if (!existingRouteOpt.isPresent() && !hasRequestRoute) {
			// 케이스 A: 기존 없음 + 요청 없음 → no-op
			finalRoute = null;
		} else if (!existingRouteOpt.isPresent() && hasRequestRoute) {
			// 케이스 B: 기존 없음 + 요청 있음 → 신규 생성
			String namespace = namespaceExtractor.extract(command.pathPrefix());
			Optional<Long> existingOwner = serviceRouteRepository.findNamespaceOwner(namespace);
			if (existingOwner.isPresent() && !existingOwner.get().equals(command.ownerId())) {
				throw new RouteNamespaceTakenException(namespace);
			}
			routeValidator.validateUpstreamUrl(command.upstreamUrl());
			routeValidator.validatePathPrefix(command.pathPrefix());
			finalRoute =
					serviceRouteRepository.save(
							ServiceRoute.create(
									command.pathPrefix(),
									command.upstreamUrl(),
									true,
									command.ownerId(),
									command.clientId()));
			triggerRefresh();
		} else if (existingRouteOpt.isPresent() && !hasRequestRoute) {
			// 케이스 C: 기존 있음 + 요청 없음 → 삭제
			serviceRouteRepository.deleteByRegisteredClientId(command.clientId());
			triggerRefresh();
			finalRoute = null;
		} else {
			// 케이스 D: 기존 있음 + 요청 있음 → 네임스페이스 불변 검증 후 업데이트
			ServiceRoute existingRoute = existingRouteOpt.get();
			String existingNamespace = namespaceExtractor.extract(existingRoute.pathPrefix());
			String newNamespace = namespaceExtractor.extract(command.pathPrefix());

			if (!existingNamespace.equals(newNamespace)) {
				throw RouteNamespaceChangeException.denied(existingNamespace, newNamespace);
			}

			routeValidator.validateUpstreamUrl(command.upstreamUrl());
			routeValidator.validatePathPrefixForUpdate(command.pathPrefix(), existingRoute.routeId());

			ServiceRoute updated =
					new ServiceRoute(
							existingRoute.routeId(),
							command.pathPrefix(),
							command.upstreamUrl(),
							existingRoute.enabled(),
							existingRoute.createdAt(),
							null, // updatedAt: JPA @LastModifiedDate가 채움
							command.ownerId(),
							command.clientId());
			finalRoute = serviceRouteRepository.save(updated);
			triggerRefresh();
		}

		// 최신 상태 반환
		Set<String> finalRedirectUris =
				sasRedirectUriManager.findRedirectUrisByClientId(command.clientId());

		// 수정 후 clientName 반영 (로컬 변수에서 가져오기)
		String finalClientName =
				existing.getClientName().equals(command.clientName())
						? existing.getClientName()
						: command.clientName();

		return new MyClientResult(
				command.clientId(),
				finalClientName,
				finalRedirectUris,
				finalRoute != null ? finalRoute.routeId() : null,
				finalRoute != null ? finalRoute.pathPrefix() : null,
				finalRoute != null ? finalRoute.upstreamUrl() : null,
				finalRoute != null ? finalRoute.enabled() : null);
	}

	/**
	 * 내 클라이언트 하드 삭제
	 *
	 * <p>service_client + SAS oauth2_registered_client + 연결 service_route를 단일 트랜잭션에서 삭제한다. 라우트가 있었으면
	 * afterCommit에 gateway refresh를 등록한다.
	 *
	 * @param command 삭제 명령
	 * @throws InvalidClientException 미존재 또는 타인 소유 시 (404 존재 은닉)
	 */
	@Override
	@Transactional
	public void deleteMyClient(DeleteMyClientCommand command) {
		// [1단계] 소유권 검증
		serviceClientRepository
				.findByClientIdAndOwnerId(command.clientId(), command.ownerId())
				.orElseThrow(InvalidClientException::new);

		// [2단계] 라우트 캐스케이드 삭제
		Optional<ServiceRoute> routeOpt =
				serviceRouteRepository.findByRegisteredClientId(command.clientId());
		if (routeOpt.isPresent()) {
			serviceRouteRepository.deleteByRegisteredClientId(command.clientId());
			triggerRefresh();
		}

		// [3단계] service_client 삭제
		serviceClientRepository.deleteByClientId(command.clientId());

		// [4단계] SAS 삭제
		sasClientRegistrar.unregisterClient(command.clientId());
	}

	/**
	 * 게이트웨이 refresh 트리거 (실패 시 경고 로그만, 롤백 없음)
	 *
	 * <p>트랜잭션이 활성화된 경우 커밋 이후에 실행한다. 트랜잭션이 없으면 즉시 실행한다(단위 테스트 등).
	 */
	private void triggerRefresh() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(
					new TransactionSynchronization() {
						@Override
						public void afterCommit() {
							doTriggerRefresh();
						}
					});
		} else {
			doTriggerRefresh();
		}
	}

	private void doTriggerRefresh() {
		try {
			gatewayRefreshClient.triggerRefresh();
		} catch (Exception e) {
			log.warn("Gateway refresh 트리거 실패: {}", e.getMessage());
		}
	}

	private MyClientResult toResult(
			ServiceClient client, ServiceRoute route, Set<String> redirectUris) {
		return new MyClientResult(
				client.getRegisteredClientId(),
				client.getClientName(),
				redirectUris,
				route != null ? route.routeId() : null,
				route != null ? route.pathPrefix() : null,
				route != null ? route.upstreamUrl() : null,
				route != null ? route.enabled() : null);
	}
}
