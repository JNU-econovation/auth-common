package com.econo.auth.client.application.port.out;

import java.util.Set;

/**
 * SAS(Spring Authorization Server) redirectUri 관리 아웃바운드 포트
 *
 * <p>Application 계층이 SAS 구현체에 직접 의존하지 않도록 격리하는 포트. redirectUri 조회·수정·저장 책임만 가진다. (최소 인터페이스 원칙)
 */
public interface SasRedirectUriManager {

	/**
	 * clientId로 클라이언트 이름 조회
	 *
	 * @param clientId 클라이언트 ID
	 * @return 클라이언트 이름. 미존재 시 null 반환.
	 */
	String findClientNameByClientId(String clientId);

	/**
	 * clientId로 redirectUri 목록 조회
	 *
	 * @param clientId 클라이언트 ID
	 * @return redirectUri 목록. 미존재 시 null 반환.
	 */
	Set<String> findRedirectUrisByClientId(String clientId);

	/**
	 * clientId의 redirectUri 목록 갱신
	 *
	 * @param clientId 클라이언트 ID
	 * @param newUris 새 redirectUri 목록
	 */
	void updateRedirectUris(String clientId, Set<String> newUris);
}
