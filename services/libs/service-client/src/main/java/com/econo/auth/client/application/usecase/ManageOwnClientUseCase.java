package com.econo.auth.client.application.usecase;

import java.util.List;
import java.util.Set;

/**
 * 셀프 클라이언트 관리 입력 포트 인터페이스
 *
 * <p>인증된 회원이 자기 소유 OAuth 클라이언트(+연결 라우트)를 직접 조회·수정·삭제하는 유스케이스를 정의한다.
 */
public interface ManageOwnClientUseCase {

	/**
	 * 셀프 클라이언트 조회·수정 결과 record
	 *
	 * @param clientId service_client.registered_client_id
	 * @param clientName 클라이언트 이름
	 * @param redirectUris 허용 리다이렉트 URI 목록
	 * @param routeId 연결 라우트 ID (null 허용 — 라우트 없는 클라이언트)
	 * @param pathPrefix 라우트 경로 접두사 (null 허용)
	 * @param upstreamUrl 업스트림 서비스 URL (null 허용)
	 * @param routeEnabled 라우트 활성화 여부 (null 허용)
	 */
	record MyClientResult(
			String clientId,
			String clientName,
			Set<String> redirectUris,
			String routeId,
			String pathPrefix,
			String upstreamUrl,
			Boolean routeEnabled) {}

	/**
	 * 클라이언트 수정 명령 record
	 *
	 * @param clientId 수정 대상 클라이언트 ID
	 * @param ownerId 요청자 회원 ID (소유권 검증용)
	 * @param clientName 새 클라이언트 이름
	 * @param redirectUris 새 허용 리다이렉트 URI 목록
	 * @param pathPrefix 새 라우트 경로 접두사 (null이면 라우트 제거 의미)
	 * @param upstreamUrl 새 업스트림 서비스 URL (null이면 라우트 제거 의미)
	 */
	record UpdateMyClientCommand(
			String clientId,
			Long ownerId,
			String clientName,
			Set<String> redirectUris,
			String pathPrefix,
			String upstreamUrl) {}

	/**
	 * 클라이언트 삭제 명령 record
	 *
	 * @param clientId 삭제 대상 클라이언트 ID
	 * @param ownerId 요청자 회원 ID (소유권 검증용)
	 */
	record DeleteMyClientCommand(String clientId, Long ownerId) {}

	/**
	 * 내 클라이언트 목록 조회
	 *
	 * @param ownerId 조회 대상 회원 ID
	 * @return 해당 회원이 소유한 클라이언트 목록 (빈 목록이면 empty list 반환)
	 */
	List<MyClientResult> listMyClients(Long ownerId);

	/**
	 * 내 클라이언트 단건 상세 조회
	 *
	 * @param clientId 조회 대상 클라이언트 ID
	 * @param ownerId 요청자 회원 ID (소유권 검증용)
	 * @return 클라이언트 상세 정보
	 * @throws com.econo.auth.client.exception.InvalidClientException 미존재 또는 타인 소유 시 (404 존재 은닉)
	 */
	MyClientResult getMyClient(String clientId, Long ownerId);

	/**
	 * 내 클라이언트 수정 (전체 표현 교체)
	 *
	 * @param command 수정 명령
	 * @return 수정 후 상태
	 * @throws com.econo.auth.client.exception.InvalidClientException 미존재 또는 타인 소유 시 (404 존재 은닉)
	 * @throws com.econo.auth.client.exception.RouteNamespaceChangeException 네임스페이스 변경 시도 시 (400)
	 */
	MyClientResult updateMyClient(UpdateMyClientCommand command);

	/**
	 * 내 클라이언트 하드 삭제 (service_client + SAS + 연결 라우트 캐스케이드)
	 *
	 * @param command 삭제 명령
	 * @throws com.econo.auth.client.exception.InvalidClientException 미존재 또는 타인 소유 시 (404 존재 은닉)
	 */
	void deleteMyClient(DeleteMyClientCommand command);
}
