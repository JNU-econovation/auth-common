package com.econo.auth.client.persistence.entity;

import com.econo.auth.client.application.domain.ServiceRoute;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** service_route 테이블 JPA 엔티티 (V9 마이그레이션 이후 스키마 기준 — FK 제거, registered_client_id nullable) */
// TODO: BaseEntity 추출
@Entity
@Table(name = "service_route")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ServiceRouteJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "route_id", nullable = false, unique = true, length = 100)
	private String routeId;

	@Column(name = "registered_client_id", nullable = true, length = 100)
	private String registeredClientId;

	@Column(name = "path_prefix", nullable = true, length = 200)
	private String pathPrefix;

	@Column(name = "upstream_url", nullable = false, length = 500)
	private String upstreamUrl;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	/**
	 * 도메인 ServiceRoute → JPA 엔티티 변환
	 *
	 * @param route 도메인 ServiceRoute
	 * @return ServiceRouteJpaEntity 인스턴스
	 */
	public static ServiceRouteJpaEntity from(ServiceRoute route) {
		ServiceRouteJpaEntity entity = new ServiceRouteJpaEntity();
		entity.routeId = route.routeId();
		entity.pathPrefix = route.pathPrefix();
		entity.upstreamUrl = route.upstreamUrl();
		entity.enabled = route.enabled();
		entity.registeredClientId = null;
		return entity;
	}

	/**
	 * 기존 엔티티의 PK를 유지하며 도메인 ServiceRoute로부터 엔티티 생성 (수정 시 JPA merge용)
	 *
	 * @param id 기존 엔티티 PK
	 * @param route 수정된 도메인 ServiceRoute
	 * @return id가 설정된 ServiceRouteJpaEntity 인스턴스
	 */
	public static ServiceRouteJpaEntity fromWithId(Long id, ServiceRoute route) {
		ServiceRouteJpaEntity entity = new ServiceRouteJpaEntity();
		entity.id = id;
		entity.routeId = route.routeId();
		entity.pathPrefix = route.pathPrefix();
		entity.upstreamUrl = route.upstreamUrl();
		entity.enabled = route.enabled();
		entity.registeredClientId = null;
		return entity;
	}

	/**
	 * JPA 엔티티 → 도메인 ServiceRoute 변환
	 *
	 * @return 도메인 ServiceRoute 인스턴스
	 */
	public ServiceRoute toDomain() {
		return new ServiceRoute(routeId, pathPrefix, upstreamUrl, enabled, createdAt, updatedAt);
	}
}
