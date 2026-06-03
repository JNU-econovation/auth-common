package com.econo.auth.client.adapter.out.persistence;

import com.econo.auth.client.application.port.out.ServiceClientRepository;
import com.econo.auth.client.domain.ServiceClient;
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
}
