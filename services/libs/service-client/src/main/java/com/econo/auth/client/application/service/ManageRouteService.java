package com.econo.auth.client.application.service;

import com.econo.auth.client.application.domain.ServiceRoute;
import com.econo.auth.client.application.repository.ServiceRouteRepository;
import com.econo.auth.client.application.usecase.ManageRouteUseCase;
import com.econo.auth.client.exception.RouteNotFoundException;
import com.econo.auth.client.exception.RouteProtectedException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 라우트 CRUD 유스케이스 구현체
 *
 * <p>SSRF 검증·보호경로·pathPrefix 중복 검증은 공유 {@link RouteValidator}에 위임한다. DB 저장, 게이트웨이 refresh 트리거를
 * 수행한다.
 *
 * <p>ApplicationServiceConfig에서 @Bean 등록 (service-client 내부 @Service 사용 안 함).
 */
@Slf4j
@RequiredArgsConstructor
public class ManageRouteService implements ManageRouteUseCase {

	private final ServiceRouteRepository serviceRouteRepository;
	private final GatewayRefreshClient gatewayRefreshClient;
	private final RouteValidator routeValidator;

	/**
	 * 라우트 생성
	 *
	 * @param command 생성 명령
	 * @return 생성된 라우트 결과
	 */
	@Override
	@Transactional
	public RouteResult createRoute(CreateRouteCommand command) {
		routeValidator.validateUpstreamUrl(command.upstreamUrl());
		routeValidator.validatePathPrefix(command.pathPrefix());

		ServiceRoute route =
				ServiceRoute.create(command.pathPrefix(), command.upstreamUrl(), command.enabled());
		ServiceRoute saved = serviceRouteRepository.save(route);

		triggerRefresh();

		return toResult(saved);
	}

	/**
	 * 라우트 수정
	 *
	 * @param routeId 수정할 라우트 UUID 문자열
	 * @param command 수정 명령
	 * @return 수정된 라우트 결과
	 */
	@Override
	@Transactional
	public RouteResult updateRoute(String routeId, UpdateRouteCommand command) {
		ServiceRoute existing =
				serviceRouteRepository
						.findById(routeId)
						.orElseThrow(() -> new RouteNotFoundException(routeId));

		// 기존 라우트의 pathPrefix가 보호경로이면 upstream 변조를 방지한다
		if (routeValidator.isProtected(existing.pathPrefix())) {
			throw new RouteProtectedException(existing.pathPrefix());
		}

		routeValidator.validateUpstreamUrl(command.upstreamUrl());
		routeValidator.validatePathPrefixForUpdate(command.pathPrefix(), routeId);

		ServiceRoute updated =
				new ServiceRoute(
						routeId,
						command.pathPrefix(),
						command.upstreamUrl(),
						command.enabled(),
						existing.createdAt(),
						null);
		ServiceRoute saved = serviceRouteRepository.save(updated);

		triggerRefresh();

		return toResult(saved);
	}

	/**
	 * 라우트 삭제
	 *
	 * @param routeId 삭제할 라우트 UUID 문자열
	 */
	@Override
	@Transactional
	public void deleteRoute(String routeId) {
		ServiceRoute route =
				serviceRouteRepository
						.findById(routeId)
						.orElseThrow(() -> new RouteNotFoundException(routeId));

		if (routeValidator.isProtected(route.pathPrefix())) {
			throw new RouteProtectedException(route.pathPrefix());
		}

		serviceRouteRepository.deleteById(routeId);

		triggerRefresh();
	}

	/**
	 * 전체 라우트 목록 조회
	 *
	 * @return 전체 라우트 결과 목록
	 */
	@Override
	@Transactional(readOnly = true)
	public List<RouteResult> listRoutes() {
		return serviceRouteRepository.findAll().stream().map(this::toResult).toList();
	}

	/**
	 * enabled=true 라우트 목록 조회 (DB 레벨 필터 — V10 인덱스 활용)
	 *
	 * @return 활성화된 라우트 결과 목록
	 */
	@Override
	@Transactional(readOnly = true)
	public List<RouteResult> listEnabledRoutes() {
		return serviceRouteRepository.findAllEnabled().stream().map(this::toResult).toList();
	}

	/**
	 * 단건 라우트 조회
	 *
	 * @param routeId 라우트 UUID 문자열
	 * @return 라우트 결과
	 */
	@Override
	@Transactional(readOnly = true)
	public RouteResult getRoute(String routeId) {
		return serviceRouteRepository
				.findById(routeId)
				.map(this::toResult)
				.orElseThrow(() -> new RouteNotFoundException(routeId));
	}

	/**
	 * 게이트웨이 refresh 트리거 (실패 시 경고 로그만, 롤백 없음)
	 *
	 * <p>트랜잭션이 활성화된 경우 <b>커밋 이후</b>에 실행한다. 커밋 전에 호출하면 게이트웨이가 auth-api에서 라우트를 다시 읽을 때 아직 커밋되지 않은
	 * 변경(삭제/추가)이 보이지 않아 직전 상태를 캐시하게 된다. 트랜잭션이 없으면 즉시 실행한다(단위 테스트 등).
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
			log.warn("Gateway refresh 트리거 실패 (최종 일관성 수용): {}", e.getMessage());
		}
	}

	private RouteResult toResult(ServiceRoute route) {
		return new RouteResult(
				route.routeId(),
				route.pathPrefix(),
				route.upstreamUrl(),
				route.enabled(),
				route.createdAt(),
				route.updatedAt());
	}
}
