package com.econo.auth.api.domain;

import lombok.Getter;

/** ServiceClient 도메인 객체 */
@Getter
public class ServiceClient {

	private final String registeredClientId;
	private final String clientName;
	private final GrantType grantType;
	private final String apiKeyHash;

	private ServiceClient(
			String registeredClientId, String clientName, GrantType grantType, String apiKeyHash) {
		this.registeredClientId = registeredClientId;
		this.clientName = clientName;
		this.grantType = grantType;
		this.apiKeyHash = apiKeyHash;
	}

	/**
	 * ServiceClient 생성 팩토리 메서드
	 *
	 * @param registeredClientId SAS 등록 클라이언트 ID
	 * @param clientName 클라이언트 이름
	 * @param grantType 그랜트 타입
	 * @param apiKeyHash SHA-256 해시된 API 키 (client_credentials 전용)
	 * @return ServiceClient 인스턴스
	 */
	public static ServiceClient create(
			String registeredClientId, String clientName, GrantType grantType, String apiKeyHash) {
		return new ServiceClient(registeredClientId, clientName, grantType, apiKeyHash);
	}
}
