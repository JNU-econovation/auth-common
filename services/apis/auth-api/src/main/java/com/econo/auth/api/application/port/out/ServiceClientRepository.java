package com.econo.auth.api.application.port.out;

import com.econo.auth.api.domain.ServiceClient;

/** ServiceClient 저장소 포트 (out) */
public interface ServiceClientRepository {

	/**
	 * ServiceClient 저장
	 *
	 * @param serviceClient 저장할 ServiceClient
	 */
	void save(ServiceClient serviceClient);

	/**
	 * 클라이언트 이름 중복 확인
	 *
	 * @param clientName 클라이언트 이름
	 * @return 중복이면 true
	 */
	boolean existsByClientName(String clientName);
}
