package com.econo.auth.client.application.usecase;

import java.util.Set;

/**
 * OAuth 클라이언트 redirectUri 관리 입력 포트 인터페이스
 *
 * <p>엄격 DIP: {@code AdminClientController}가 {@code ClientRedirectUriService} 구현체를 직접 주입하는 것을 막는
 * seam. {@code LoginRedirectResolver}(api.application.service)도 이 인터페이스에 의존한다.
 */
public interface ClientRedirectUriUseCase {

	/**
	 * 클라이언트 정보
	 *
	 * @param clientId 클라이언트 ID
	 * @param clientName 클라이언트 이름
	 * @param redirectUris redirectUri 목록
	 */
	record ClientInfo(String clientId, String clientName, Set<String> redirectUris) {}

	/** clientId로 클라이언트 조회 */
	ClientInfo findByClientId(String clientId);

	/** redirectUri 추가 */
	Set<String> addRedirectUri(String clientId, String uri);

	/** redirectUri 제거 */
	Set<String> removeRedirectUri(String clientId, String uri);

	/** redirectUri 전체 교체 */
	Set<String> replaceRedirectUris(String clientId, Set<String> uris);

	/**
	 * 등록된 모든 클라이언트의 redirectUri에서 CORS 허용 오리진 추출
	 *
	 * @param additionalOrigins 환경변수로 추가 등록된 오리진 목록
	 * @return 허용 오리진 목록
	 */
	Set<String> extractAllowedOrigins(Set<String> additionalOrigins);
}
