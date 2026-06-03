package com.econo.auth.client.domain;

import lombok.Getter;
import org.springframework.lang.Nullable;

/** ServiceClient 도메인 객체 */
@Getter
public class ServiceClient {

	private final String registeredClientId;
	private final String clientName;
	@Nullable private final GrantType grantType;
	@Nullable private final String apiKeyHash;

	private ServiceClient(
			String registeredClientId,
			String clientName,
			@Nullable GrantType grantType,
			@Nullable String apiKeyHash) {
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
	 * @param grantType 그랜트 타입. null이면 client_credentials 디폴트로 처리된 것임.
	 * @param apiKeyHash SHA-256 해시된 API 키. 항상 null — 향후 API key 채널 도입 시 부활 예정.
	 * @return ServiceClient 인스턴스
	 */
	public static ServiceClient create(
			String registeredClientId,
			String clientName,
			@Nullable GrantType grantType,
			@Nullable String apiKeyHash) {
		return new ServiceClient(registeredClientId, clientName, grantType, apiKeyHash);
	}
}
