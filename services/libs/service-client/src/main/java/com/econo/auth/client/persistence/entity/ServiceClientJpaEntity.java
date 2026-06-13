package com.econo.auth.client.persistence.entity;

import com.econo.auth.client.application.domain.GrantType;
import com.econo.auth.client.application.domain.ServiceClient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** service_client 테이블 JPA 엔티티 */
@Entity
@Table(name = "service_client")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ServiceClientJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "registered_client_id", nullable = false, unique = true, length = 100)
	private String registeredClientId;

	@Column(name = "client_name", nullable = false, unique = true, length = 100)
	private String clientName;

	@Enumerated(EnumType.STRING)
	@Column(name = "grant_type", nullable = true, length = 30)
	private GrantType grantType;

	@Column(name = "api_key_hash", length = 64)
	private String apiKeyHash;

	@Column(name = "owner_id", nullable = true)
	private Long ownerId;

	@Column(name = "client_secret_hash", nullable = true, length = 72)
	private String clientSecretHash;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/**
	 * 도메인 ServiceClient → JPA 엔티티 변환
	 *
	 * @param serviceClient 도메인 ServiceClient
	 * @return ServiceClientJpaEntity 인스턴스
	 */
	public static ServiceClientJpaEntity from(ServiceClient serviceClient) {
		ServiceClientJpaEntity entity = new ServiceClientJpaEntity();
		entity.registeredClientId = serviceClient.getRegisteredClientId();
		entity.clientName = serviceClient.getClientName();
		entity.grantType = serviceClient.getGrantType();
		entity.apiKeyHash = serviceClient.getApiKeyHash();
		entity.ownerId = serviceClient.getOwnerId();
		entity.clientSecretHash = serviceClient.getClientSecretHash();
		return entity;
	}

	/**
	 * JPA 엔티티 → 도메인 ServiceClient 변환
	 *
	 * @return 도메인 ServiceClient 인스턴스
	 */
	public ServiceClient toDomain() {
		return ServiceClient.create(
				registeredClientId, clientName, grantType, apiKeyHash, ownerId, clientSecretHash);
	}
}
