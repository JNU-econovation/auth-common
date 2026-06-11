package com.econo.auth.client.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** service_client 테이블 Spring Data JPA Repository */
public interface ServiceClientJpaRepository extends JpaRepository<ServiceClientJpaEntity, Long> {

	/**
	 * 클라이언트 이름 중복 확인
	 *
	 * @param clientName 클라이언트 이름
	 * @return 중복이면 true
	 */
	boolean existsByClientName(String clientName);

	@org.springframework.data.jpa.repository.Query(
			"SELECT e.registeredClientId FROM ServiceClientJpaEntity e")
	java.util.List<String> findAllRegisteredClientIds();

	/**
	 * 소유자 회원 ID별 클라이언트 수 조회
	 *
	 * @param ownerId 소유자 회원 ID
	 * @return 해당 회원이 소유한 클라이언트 수
	 */
	long countByOwnerId(Long ownerId);
}
