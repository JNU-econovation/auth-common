package com.econo.auth.client.application.repository;

import com.econo.auth.client.application.domain.ServiceRoute;
import java.util.List;
import java.util.Optional;

/** ServiceRoute 저장소 아웃바운드 포트 */
public interface ServiceRouteRepository {

	/**
	 * 라우트 저장
	 *
	 * @param route 저장할 ServiceRoute
	 * @return 저장된 ServiceRoute (타임스탬프 포함)
	 */
	ServiceRoute save(ServiceRoute route);

	/**
	 * 전체 라우트 목록 조회 (createdAt 오름차순)
	 *
	 * @return 전체 라우트 목록
	 */
	List<ServiceRoute> findAll();

	/**
	 * routeId로 단건 조회
	 *
	 * @param routeId 라우트 UUID 문자열
	 * @return Optional ServiceRoute
	 */
	Optional<ServiceRoute> findById(String routeId);

	/**
	 * routeId로 삭제
	 *
	 * @param routeId 라우트 UUID 문자열
	 */
	void deleteById(String routeId);

	/**
	 * pathPrefix 중복 확인
	 *
	 * @param pathPrefix 경로 접두사
	 * @return 중복이면 true
	 */
	boolean existsByPathPrefix(String pathPrefix);

	/**
	 * 자신을 제외한 pathPrefix 중복 확인 (수정 시 자기 자신 제외)
	 *
	 * @param pathPrefix 경로 접두사
	 * @param routeId 제외할 라우트 UUID 문자열
	 * @return 다른 라우트에 중복이면 true
	 */
	boolean existsByPathPrefixAndRouteIdNot(String pathPrefix, String routeId);

	/**
	 * enabled=true 라우트 전량 조회 (게이트웨이 초기 로드용)
	 *
	 * @return 활성화된 라우트 목록
	 */
	List<ServiceRoute> findAllEnabled();

	/**
	 * 네임스페이스 선점 owner 조회
	 *
	 * <p>해당 네임스페이스에 속하는 라우트의 ownerId를 반환한다. 결과가 없으면 {@link java.util.Optional#empty()}.
	 *
	 * @param namespace 네임스페이스 문자열 (두 번째 세그먼트)
	 * @return 선점한 ownerId (없으면 empty)
	 */
	Optional<Long> findNamespaceOwner(String namespace);

	/**
	 * registeredClientId로 라우트 단건 조회 (클라이언트당 라우트 최대 1개 전제 — 상세/수정용)
	 *
	 * @param registeredClientId service_client.registered_client_id (= 라우트의 소유 클라이언트)
	 * @return 연관 라우트 (없으면 empty)
	 */
	Optional<ServiceRoute> findByRegisteredClientId(String registeredClientId);

	/**
	 * registeredClientId 배치 조회 — N+1 방지, listMyClients용
	 *
	 * @param registeredClientIds 조회 대상 clientId 목록
	 * @return 연관 라우트 목록 (registeredClientId가 일치하는 것만)
	 */
	List<ServiceRoute> findByRegisteredClientIdIn(List<String> registeredClientIds);

	/**
	 * registeredClientId로 연결 라우트 hard delete (클라이언트 삭제·라우트 제거 캐스케이드)
	 *
	 * @param registeredClientId 삭제 대상 클라이언트 ID
	 */
	void deleteByRegisteredClientId(String registeredClientId);
}
