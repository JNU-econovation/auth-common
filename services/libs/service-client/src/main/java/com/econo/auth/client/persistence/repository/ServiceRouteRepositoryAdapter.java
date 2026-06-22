package com.econo.auth.client.persistence.repository;

import com.econo.auth.client.application.domain.ServiceRoute;
import com.econo.auth.client.application.repository.ServiceRouteRepository;
import com.econo.auth.client.persistence.entity.ServiceRouteJpaEntity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** ServiceRouteRepository 아웃바운드 포트 구현체 */
@Component
@RequiredArgsConstructor
public class ServiceRouteRepositoryAdapter implements ServiceRouteRepository {

	private final ServiceRouteJpaRepository serviceRouteJpaRepository;

	/**
	 * 라우트 저장
	 *
	 * @param route 저장할 ServiceRoute
	 * @return 저장된 ServiceRoute (타임스탬프 포함)
	 */
	@Override
	@Transactional
	public ServiceRoute save(ServiceRoute route) {
		// 기존 routeId가 있으면 업데이트, 없으면 신규 저장
		Optional<ServiceRouteJpaEntity> existing =
				serviceRouteJpaRepository.findByRouteId(route.routeId());
		if (existing.isPresent()) {
			ServiceRouteJpaEntity entity = existing.get();
			ServiceRouteJpaEntity updated = ServiceRouteJpaEntity.from(route);
			// 수정 시 기존 엔티티를 merge하기 위해 id를 이용
			return serviceRouteJpaRepository.save(mergeEntity(entity, route)).toDomain();
		}
		ServiceRouteJpaEntity entity = ServiceRouteJpaEntity.from(route);
		return serviceRouteJpaRepository.save(entity).toDomain();
	}

	/**
	 * 전체 라우트 목록 조회 (createdAt 오름차순)
	 *
	 * @return 전체 라우트 목록
	 */
	@Override
	@Transactional(readOnly = true)
	public List<ServiceRoute> findAll() {
		return serviceRouteJpaRepository.findAllByOrderByCreatedAtAsc().stream()
				.map(ServiceRouteJpaEntity::toDomain)
				.toList();
	}

	/**
	 * routeId로 단건 조회
	 *
	 * @param routeId 라우트 UUID 문자열
	 * @return Optional ServiceRoute
	 */
	@Override
	@Transactional(readOnly = true)
	public Optional<ServiceRoute> findById(String routeId) {
		return serviceRouteJpaRepository.findByRouteId(routeId).map(ServiceRouteJpaEntity::toDomain);
	}

	/**
	 * routeId로 삭제
	 *
	 * @param routeId 라우트 UUID 문자열
	 */
	@Override
	@Transactional
	public void deleteById(String routeId) {
		serviceRouteJpaRepository.deleteByRouteId(routeId);
	}

	/**
	 * pathPrefix 중복 확인
	 *
	 * @param pathPrefix 경로 접두사
	 * @return 중복이면 true
	 */
	@Override
	@Transactional(readOnly = true)
	public boolean existsByPathPrefix(String pathPrefix) {
		return serviceRouteJpaRepository.existsByPathPrefix(pathPrefix);
	}

	/**
	 * 자신 제외 pathPrefix 중복 확인
	 *
	 * @param pathPrefix 경로 접두사
	 * @param routeId 제외할 라우트 UUID 문자열
	 * @return 다른 라우트에 중복이면 true
	 */
	@Override
	@Transactional(readOnly = true)
	public boolean existsByPathPrefixAndRouteIdNot(String pathPrefix, String routeId) {
		return serviceRouteJpaRepository.existsByPathPrefixAndRouteIdNot(pathPrefix, routeId);
	}

	/**
	 * enabled=true 라우트 전량 조회
	 *
	 * @return 활성화된 라우트 목록
	 */
	@Override
	@Transactional(readOnly = true)
	public List<ServiceRoute> findAllEnabled() {
		return serviceRouteJpaRepository.findAllByEnabled(true).stream()
				.map(ServiceRouteJpaEntity::toDomain)
				.toList();
	}

	/**
	 * 네임스페이스 선점 owner 조회
	 *
	 * <p>어드민 라우트(owner_id=NULL)와 셀프 라우트가 공존하는 경우 NULL(어드민 라우트)을 제외한 첫 번째 non-null ownerId를 반환한다.
	 *
	 * @param namespace 네임스페이스 문자열
	 * @return 선점한 ownerId (없으면 empty)
	 */
	@Override
	@Transactional(readOnly = true)
	public Optional<Long> findNamespaceOwner(String namespace) {
		return serviceRouteJpaRepository.findOwnerIdsByNamespace(namespace).stream()
				.filter(Objects::nonNull)
				.findFirst();
	}

	/** 기존 엔티티에 수정 내용을 반영한 새 엔티티 생성 (JPA merge를 위해 기존 id 사용) */
	private ServiceRouteJpaEntity mergeEntity(ServiceRouteJpaEntity existing, ServiceRoute route) {
		return ServiceRouteJpaEntity.fromWithId(existing.getId(), route);
	}
}
