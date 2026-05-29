package com.econo.auth.api.adapter.out.persistence;

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
}
