package com.econo.auth.client.persistence.repository;

import com.econo.auth.client.application.domain.ServiceClient;
import com.econo.auth.client.application.repository.ServiceClientRepository;
import com.econo.auth.client.persistence.entity.ServiceClientJpaEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** ServiceClientRepository 포트 구현체 */
@Component
@RequiredArgsConstructor
public class ServiceClientRepositoryAdapter implements ServiceClientRepository {

	private final ServiceClientJpaRepository serviceClientJpaRepository;

	@Override
	@Transactional
	public void save(ServiceClient serviceClient) {
		serviceClientJpaRepository.save(ServiceClientJpaEntity.from(serviceClient));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean existsByClientName(String clientName) {
		return serviceClientJpaRepository.existsByClientName(clientName);
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.List<String> findAllRegisteredClientIds() {
		return serviceClientJpaRepository.findAllRegisteredClientIds();
	}

	@Override
	@Transactional(readOnly = true)
	public long countByOwnerId(Long ownerId) {
		return serviceClientJpaRepository.countByOwnerId(ownerId);
	}
}
