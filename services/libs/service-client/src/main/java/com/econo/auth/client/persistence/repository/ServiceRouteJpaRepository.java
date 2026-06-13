package com.econo.auth.client.persistence.repository;

import com.econo.auth.client.persistence.entity.ServiceRouteJpaEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

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
}
