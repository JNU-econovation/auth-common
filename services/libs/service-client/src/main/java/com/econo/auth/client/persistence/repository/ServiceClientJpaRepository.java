package com.econo.auth.client.persistence.repository;

import com.econo.auth.client.persistence.entity.ServiceClientJpaEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** service_client 테이블 Spring Data JPA Repository */
public interface ServiceClientJpaRepository extends JpaRepository<ServiceClientJpaEntity, Long> {

	/**
	 * 클라이언트 이름 중복 확인
	 *
	 * @param clientName 클라이언트 이름
	 * @return 중복이면 true
	 */
	boolean existsByClientName(String clientName);

	@Query("SELECT e.registeredClientId FROM ServiceClientJpaEntity e")
	List<String> findAllRegisteredClientIds();

	/**
	 * 소유자 회원 ID별 클라이언트 수 조회
	 *
	 * @param ownerId 소유자 회원 ID
	 * @return 해당 회원이 소유한 클라이언트 수
	 */
	long countByOwnerId(Long ownerId);

	/**
	 * ownerId로 클라이언트 목록 조회 (셀프 관리 목록 조회용)
	 *
	 * @param ownerId 소유자 회원 ID
	 * @return 해당 회원이 소유한 클라이언트 목록
	 */
	List<ServiceClientJpaEntity> findByOwnerId(Long ownerId);

	/**
	 * registeredClientId + ownerId 복합 조회 (소유권 검증 겸 단건 조회)
	 *
	 * @param registeredClientId 클라이언트 ID
	 * @param ownerId 소유자 회원 ID
	 * @return 소유권이 일치하면 ServiceClientJpaEntity, 없으면 empty
	 */
	Optional<ServiceClientJpaEntity> findByRegisteredClientIdAndOwnerId(
			String registeredClientId, Long ownerId);

	/**
	 * registeredClientId로 hard delete (JPQL — @Modifying)
	 *
	 * @param registeredClientId 삭제할 클라이언트 ID
	 */
	@Modifying
	@Query("DELETE FROM ServiceClientJpaEntity e WHERE e.registeredClientId = :registeredClientId")
	void deleteByRegisteredClientId(@Param("registeredClientId") String registeredClientId);

	/**
	 * registeredClientId 기준 clientName JPQL UPDATE (불변 도메인 save 대신 사용 — PK-less INSERT 방지)
	 *
	 * <p>clearAutomatically=true로 1차 캐시를 비워 직후 조회 시 최신 값을 반환한다.
	 *
	 * @param clientId 수정할 클라이언트 registered_client_id
	 * @param newName 새 클라이언트 이름
	 */
	@Modifying(clearAutomatically = true)
	@Query(
			"UPDATE ServiceClientJpaEntity e SET e.clientName = :newName WHERE e.registeredClientId = :clientId")
	void updateClientNameByRegisteredClientId(
			@Param("clientId") String clientId, @Param("newName") String newName);
}
