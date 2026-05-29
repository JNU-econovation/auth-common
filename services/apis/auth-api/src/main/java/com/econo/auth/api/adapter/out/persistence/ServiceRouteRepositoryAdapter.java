package com.econo.auth.api.adapter.out.persistence;

import com.econo.auth.api.application.port.out.ServiceRouteRepository;
import com.econo.auth.api.domain.ServiceRoute;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** ServiceRouteRepository 포트 구현체 */
@Component
@RequiredArgsConstructor
public class ServiceRouteRepositoryAdapter implements ServiceRouteRepository {

	private final ServiceRouteJpaRepository serviceRouteJpaRepository;

	@Override
	@Transactional
	public void save(ServiceRoute serviceRoute) {
		serviceRouteJpaRepository.save(ServiceRouteJpaEntity.from(serviceRoute));
	}

	@Override
	@Transactional(readOnly = true)
	public List<ServiceRoute> findAll() {
		return serviceRouteJpaRepository.findAllByEnabledTrue().stream()
				.map(ServiceRouteJpaEntity::toDomain)
				.toList();
	}
}
