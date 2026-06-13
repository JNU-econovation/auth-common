package com.econo.auth.client.application.domain;

import lombok.Getter;
import org.springframework.lang.Nullable;

/** ServiceClient 도메인 객체 */
@Getter
public class ServiceClient {

	private final String registeredClientId;
	private final String clientName;
	@Nullable private final GrantType grantType;
	@Nullable private final String apiKeyHash;
	@Nullable private final Long ownerId;
	@Nullable private final String clientSecretHash;

	private ServiceClient(
			String registeredClientId,
			String clientName,
			@Nullable GrantType grantType,
			@Nullable String apiKeyHash,
			@Nullable Long ownerId,
			@Nullable String clientSecretHash) {
		this.registeredClientId = registeredClientId;
		this.clientName = clientName;
		this.grantType = grantType;
		this.apiKeyHash = apiKeyHash;
		this.ownerId = ownerId;
		this.clientSecretHash = clientSecretHash;
	}

	/**
	 * ServiceClient 생성 팩토리 메서드 (하위 호환 — ownerId=null, clientSecretHash=null)
	 *
	 * @param registeredClientId SAS 등록 클라이언트 ID
	 * @param clientName 클라이언트 이름
	 * @param grantType 그랜트 타입. null이면 client_credentials 디폴트로 처리된 것임.
	 * @param apiKeyHash SHA-256 해시된 API 키. 항상 null — 향후 API key 채널 도입 시 부활 예정.
	 * @return ServiceClient 인스턴스
	 */
	public static ServiceClient create(
			String registeredClientId,
			String clientName,
			@Nullable GrantType grantType,
			@Nullable String apiKeyHash) {
		return create(registeredClientId, clientName, grantType, apiKeyHash, null, null);
	}

	/**
	 * ServiceClient 생성 팩토리 메서드 (ownerId + clientSecretHash 포함)
	 *
	 * @param registeredClientId SAS 등록 클라이언트 ID
	 * @param clientName 클라이언트 이름
	 * @param grantType 그랜트 타입. null이면 client_credentials 디폴트로 처리된 것임.
	 * @param apiKeyHash SHA-256 해시된 API 키. 항상 null — 향후 API key 채널 도입 시 부활 예정.
	 * @param ownerId 클라이언트 소유자 회원 ID. 셀프 등록 시 설정, ADMIN 등록 시 null.
	 * @param clientSecretHash BCrypt 해시된 클라이언트 시크릿. 셀프 등록 시 설정, ADMIN 등록 시 null.
	 * @return ServiceClient 인스턴스
	 */
	public static ServiceClient create(
			String registeredClientId,
			String clientName,
			@Nullable GrantType grantType,
			@Nullable String apiKeyHash,
			@Nullable Long ownerId,
			@Nullable String clientSecretHash) {
		return new ServiceClient(
				registeredClientId, clientName, grantType, apiKeyHash, ownerId, clientSecretHash);
	}
}
