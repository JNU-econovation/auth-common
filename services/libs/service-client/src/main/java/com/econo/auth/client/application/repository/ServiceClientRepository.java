package com.econo.auth.client.application.repository;

import com.econo.auth.client.application.domain.ServiceClient;
import java.util.List;
import java.util.Optional;

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
	List<String> findAllRegisteredClientIds();

	/**
	 * 소유자 회원 ID별 등록 클라이언트 수 조회
	 *
	 * @param ownerId 소유자 회원 ID
	 * @return 해당 회원이 소유한 클라이언트 수
	 */
	long countByOwnerId(Long ownerId);

	/**
	 * ownerId로 클라이언트 목록 조회 (셀프 관리 목록 조회용)
	 *
	 * @param ownerId 소유자 회원 ID
	 * @return 해당 회원이 소유한 클라이언트 목록
	 */
	List<ServiceClient> findByOwnerId(Long ownerId);

	/**
	 * clientId + ownerId 복합 조회 — 소유권 검증 겸 단건 조회 (타인 소유 → empty로 404 존재 은닉)
	 *
	 * @param clientId service_client.registered_client_id
	 * @param ownerId service_client.owner_id
	 * @return 소유권이 일치하면 ServiceClient, 미존재 또는 타인 소유이면 Optional.empty()
	 */
	Optional<ServiceClient> findByClientIdAndOwnerId(String clientId, Long ownerId);

	/**
	 * clientId로 hard delete (service_client 레코드 삭제)
	 *
	 * @param clientId service_client.registered_client_id
	 */
	void deleteByClientId(String clientId);

	/**
	 * clientName만 수정 (PUT diff용) — 불변 도메인 save 대신 JPQL UPDATE로 처리해 PK-less INSERT 방지
	 *
	 * @param clientId service_client.registered_client_id
	 * @param newName 새 클라이언트 이름
	 */
	void updateClientName(String clientId, String newName);
}
