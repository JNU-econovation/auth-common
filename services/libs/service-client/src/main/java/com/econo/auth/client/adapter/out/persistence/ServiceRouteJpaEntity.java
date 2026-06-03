package com.econo.auth.client.adapter.out.persistence;

import com.econo.auth.client.domain.ServiceRoute;
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

/** service_route 테이블 JPA 엔티티 */
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

	@Column(name = "registered_client_id", nullable = false, length = 100)
	private String registeredClientId;

	@Column(name = "path_prefix", length = 200)
	private String pathPrefix;

	@Column(name = "upstream_url", nullable = false, length = 500)
	private String upstreamUrl;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	/**
	 * 도메인 ServiceRoute → JPA 엔티티 변환
	 *
	 * @param serviceRoute 도메인 ServiceRoute
	 * @return ServiceRouteJpaEntity 인스턴스
	 */
	public static ServiceRouteJpaEntity from(ServiceRoute serviceRoute) {
		ServiceRouteJpaEntity entity = new ServiceRouteJpaEntity();
		entity.routeId = serviceRoute.routeId();
		entity.registeredClientId = serviceRoute.clientId();
		entity.pathPrefix = serviceRoute.pathPrefix();
		entity.upstreamUrl = serviceRoute.upstreamUrl();
		entity.enabled = true;
		return entity;
	}

	/**
	 * JPA 엔티티 → 도메인 ServiceRoute 변환
	 *
	 * @return 도메인 ServiceRoute 인스턴스
	 */
	public ServiceRoute toDomain() {
		return new ServiceRoute(routeId, registeredClientId, upstreamUrl, pathPrefix);
	}
}
