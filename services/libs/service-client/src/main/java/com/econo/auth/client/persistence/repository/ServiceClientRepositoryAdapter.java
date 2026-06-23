package com.econo.auth.client.persistence.repository;

import com.econo.auth.client.application.domain.ServiceClient;
import com.econo.auth.client.application.repository.ServiceClientRepository;
import com.econo.auth.client.persistence.entity.ServiceClientJpaEntity;
import java.util.List;
import java.util.Optional;
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
	public List<String> findAllRegisteredClientIds() {
		return serviceClientJpaRepository.findAllRegisteredClientIds();
	}

	@Override
	@Transactional(readOnly = true)
	public long countByOwnerId(Long ownerId) {
		return serviceClientJpaRepository.countByOwnerId(ownerId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ServiceClient> findByOwnerId(Long ownerId) {
		return serviceClientJpaRepository.findByOwnerId(ownerId).stream()
				.map(ServiceClientJpaEntity::toDomain)
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ServiceClient> findByClientIdAndOwnerId(String clientId, Long ownerId) {
		return serviceClientJpaRepository
				.findByRegisteredClientIdAndOwnerId(clientId, ownerId)
				.map(ServiceClientJpaEntity::toDomain);
	}

	@Override
	@Transactional
	public void deleteByClientId(String clientId) {
		serviceClientJpaRepository.deleteByRegisteredClientId(clientId);
	}

	@Override
	@Transactional
	public void updateClientName(String clientId, String newName) {
		serviceClientJpaRepository.updateClientNameByRegisteredClientId(clientId, newName);
	}
}
