package com.econo.auth.client.application.usecase;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 라우트 CRUD 인바운드 포트 인터페이스
 *
 * <p>Command/Result 는 이 인터페이스 내부 record로 선언하여 {@link
 * com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase} 패턴을 미러링한다.
 */
public interface ManageRouteUseCase {

	/**
	 * 라우트 생성 명령
	 *
	 * @param pathPrefix 경로 접두사
	 * @param upstreamUrl 업스트림 서비스 URL
	 * @param enabled 활성화 여부
	 */
	record CreateRouteCommand(String pathPrefix, String upstreamUrl, boolean enabled) {}

	/**
	 * 라우트 수정 명령
	 *
	 * @param pathPrefix 경로 접두사
	 * @param upstreamUrl 업스트림 서비스 URL
	 * @param enabled 활성화 여부
	 */
	record UpdateRouteCommand(String pathPrefix, String upstreamUrl, boolean enabled) {}

	/**
	 * 라우트 결과
	 *
	 * @param routeId 라우트 UUID 문자열
	 * @param pathPrefix 경로 접두사
	 * @param upstreamUrl 업스트림 서비스 URL
	 * @param enabled 활성화 여부
	 * @param createdAt 생성 시각
	 * @param updatedAt 수정 시각
	 */
	record RouteResult(
			String routeId,
			String pathPrefix,
			String upstreamUrl,
			boolean enabled,
			LocalDateTime createdAt,
			LocalDateTime updatedAt) {}

	/**
	 * 라우트 생성
	 *
	 * @param command 생성 명령
	 * @return 생성된 라우트 결과
	 */
	RouteResult createRoute(CreateRouteCommand command);

	/**
	 * 라우트 수정
	 *
	 * @param routeId 수정할 라우트 UUID 문자열
	 * @param command 수정 명령
	 * @return 수정된 라우트 결과
	 */
	RouteResult updateRoute(String routeId, UpdateRouteCommand command);

	/**
	 * 라우트 삭제
	 *
	 * @param routeId 삭제할 라우트 UUID 문자열
	 */
	void deleteRoute(String routeId);

	/**
	 * 전체 라우트 목록 조회
	 *
	 * @return 전체 라우트 결과 목록 (createdAt 오름차순)
	 */
	List<RouteResult> listRoutes();

	/**
	 * 단건 라우트 조회
	 *
	 * @param routeId 라우트 UUID 문자열
	 * @return 라우트 결과
	 */
	RouteResult getRoute(String routeId);

	/**
	 * enabled=true 라우트 목록 조회 (게이트웨이 초기 로드용)
	 *
	 * <p>DB 레벨에서 enabled 필터링하여 V10 인덱스(idx_service_route_enabled)를 활용한다.
	 *
	 * @return 활성화된 라우트 결과 목록
	 */
	List<RouteResult> listEnabledRoutes();
}
