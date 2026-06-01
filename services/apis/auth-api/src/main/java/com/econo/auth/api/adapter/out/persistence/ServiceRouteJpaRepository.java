package com.econo.auth.api.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** service_route 테이블 Spring Data JPA Repository */
public interface ServiceRouteJpaRepository extends JpaRepository<ServiceRouteJpaEntity, Long> {

	/**
	 * enabled=true인 모든 라우트 조회
	 *
	 * @return 활성화된 라우트 목록
	 */
	List<ServiceRouteJpaEntity> findAllByEnabledTrue();
}
