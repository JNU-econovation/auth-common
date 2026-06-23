package com.econo.auth.client.persistence.repository;

import com.econo.auth.client.persistence.entity.ServiceRouteJpaEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** service_route 테이블 Spring Data JPA Repository */
public interface ServiceRouteJpaRepository extends JpaRepository<ServiceRouteJpaEntity, Long> {

	/**
	 * 전체 라우트 목록 createdAt 오름차순
	 *
	 * @return createdAt 오름차순 전체 목록
	 */
	List<ServiceRouteJpaEntity> findAllByOrderByCreatedAtAsc();

	/**
	 * enabled 상태 필터 조회
	 *
	 * @param enabled 활성화 여부
	 * @return 해당 enabled 상태의 라우트 목록
	 */
	List<ServiceRouteJpaEntity> findAllByEnabled(boolean enabled);

	/**
	 * routeId로 단건 조회
	 *
	 * @param routeId 라우트 UUID 문자열
	 * @return Optional ServiceRouteJpaEntity
	 */
	Optional<ServiceRouteJpaEntity> findByRouteId(String routeId);

	/**
	 * pathPrefix 중복 확인
	 *
	 * @param pathPrefix 경로 접두사
	 * @return 중복이면 true
	 */
	boolean existsByPathPrefix(String pathPrefix);

	/**
	 * 자신 제외 pathPrefix 중복 확인
	 *
	 * @param pathPrefix 경로 접두사
	 * @param routeId 제외할 라우트 UUID 문자열
	 * @return 다른 라우트에 중복이면 true
	 */
	boolean existsByPathPrefixAndRouteIdNot(String pathPrefix, String routeId);

	/**
	 * routeId로 삭제
	 *
	 * @param routeId 라우트 UUID 문자열
	 */
	@Modifying
	@Query("DELETE FROM ServiceRouteJpaEntity e WHERE e.routeId = :routeId")
	void deleteByRouteId(String routeId);

	/**
	 * 네임스페이스 선점 ownerId 목록 조회
	 *
	 * <p>pathPrefix가 /api/{namespace}/ 로 시작하거나 /api/{namespace} 와 정확히 일치하는 라우트의 ownerId를 반환한다.
	 * text_pattern_ops 인덱스(V12)를 활용한 LIKE prefix 탐색. 어드민 라우트(owner_id=NULL)와 셀프 라우트가 공존할 수 있으므로 List로
	 * 반환한다.
	 *
	 * @param namespace 네임스페이스 문자열 (두 번째 세그먼트)
	 * @return 선점한 ownerId 목록 (NULL 포함 가능)
	 */
	@Query(
			"SELECT DISTINCT e.ownerId FROM ServiceRouteJpaEntity e WHERE e.pathPrefix LIKE"
					+ " CONCAT('/api/', :namespace, '/%') OR e.pathPrefix = CONCAT('/api/', :namespace)"
					+ " OR e.pathPrefix = CONCAT('/api/', :namespace, '/**')")
	List<Long> findOwnerIdsByNamespace(@Param("namespace") String namespace);

	/**
	 * registeredClientId로 라우트 단건 조회 (클라이언트당 라우트 1개 전제 — 상세/수정용)
	 *
	 * @param registeredClientId 연관 클라이언트 ID
	 * @return Optional ServiceRouteJpaEntity
	 */
	Optional<ServiceRouteJpaEntity> findByRegisteredClientId(String registeredClientId);

	/**
	 * registeredClientId 배치 조회 — N+1 방지, listMyClients용
	 *
	 * @param registeredClientIds 조회 대상 clientId 목록
	 * @return 연관 라우트 목록
	 */
	List<ServiceRouteJpaEntity> findByRegisteredClientIdIn(List<String> registeredClientIds);

	/**
	 * registeredClientId로 라우트 hard delete (클라이언트 삭제 시 캐스케이드용 — JPQL)
	 *
	 * @param registeredClientId 삭제 대상 클라이언트 ID
	 */
	@Modifying
	@Query("DELETE FROM ServiceRouteJpaEntity e WHERE e.registeredClientId = :registeredClientId")
	void deleteAllByRegisteredClientId(@Param("registeredClientId") String registeredClientId);
}
