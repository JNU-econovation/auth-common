package com.econo.auth.client.application.repository;

import java.util.Set;

/**
 * SAS(Spring Authorization Server) 클라이언트 등록 아웃바운드 포트
 *
 * <p>Application 계층이 SAS 인프라 타입({@code RegisteredClient})에 직접 의존하지 않도록 격리한다.
 */
public interface SasClientRegistrar {

	/**
	 * Authorization Code 클라이언트 등록 (PKCE 필수)
	 *
	 * @param clientId 클라이언트 ID (UUID)
	 * @param clientName 클라이언트 이름
	 * @param redirectUris 허용 리다이렉트 URI 목록
	 */
	void registerAuthorizationCodeClient(
			String clientId, String clientName, Set<String> redirectUris);

	/**
	 * SAS oauth2_registered_client 하드 삭제
	 *
	 * <p>SAS {@code RegisteredClientRepository}는 표준 delete 메서드를 제공하지 않는다. 구현체는 JdbcTemplate으로 직접
	 * DELETE 쿼리를 실행한다 (SAS 1.x 테이블명 의존성 수반).
	 *
	 * @param clientId 삭제할 클라이언트 ID
	 */
	void unregisterClient(String clientId);

	/**
	 * SAS oauth2_registered_client 클라이언트 이름 수정
	 *
	 * @param clientId 수정할 클라이언트 ID
	 * @param newName 새 클라이언트 이름
	 */
	void updateClientName(String clientId, String newName);
}
