package com.econo.auth.client.application.port.out;

import com.econo.auth.client.domain.ServiceClient;

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

	/** 등록된 모든 클라이언트의 registered_client_id 목록 (CORS 오리진 추출용) */
	java.util.List<String> findAllRegisteredClientIds();

	/**
	 * 소유자 회원 ID별 등록 클라이언트 수 조회
	 *
	 * @param ownerId 소유자 회원 ID
	 * @return 해당 회원이 소유한 클라이언트 수
	 */
	long countByOwnerId(Long ownerId);
}
