package com.econo.auth.client.application.usecase;

import java.util.Set;

/**
 * OAuth 클라이언트 등록 입력 포트 인터페이스
 *
 * <p>엄격 DIP: {@code AdminClientController}, {@code ClientController}가 {@code
 * RegisterOAuthClientService} 구현체를 직접 주입하는 것을 막는 seam.
 */
public interface RegisterOAuthClientUseCase {

	/**
	 * OAuth 클라이언트 등록 명령
	 *
	 * @param clientName 클라이언트 이름
	 * @param redirectUris 허용 redirect URI 목록 (필수)
	 */
	record RegisterOAuthClientCommand(String clientName, Set<String> redirectUris) {}

	/**
	 * OAuth 클라이언트 등록 결과
	 *
	 * @param clientId 등록된 클라이언트 ID
	 */
	record RegisterOAuthClientResult(String clientId) {}

	/**
	 * 셀프 등록 OAuth 클라이언트 명령
	 *
	 * @param clientName 클라이언트 이름
	 * @param redirectUris 허용 redirect URI 목록 (필수)
	 * @param ownerId 클라이언트 소유자 회원 ID
	 */
	record SelfRegisterOAuthClientCommand(
			String clientName, Set<String> redirectUris, Long ownerId) {}

	/**
	 * 셀프 등록 OAuth 클라이언트 결과
	 *
	 * @param clientId 등록된 클라이언트 ID
	 * @param clientSecret 1회 노출 평문 clientSecret
	 */
	record SelfRegisterOAuthClientResult(String clientId, String clientSecret) {}

	/**
	 * OAuth 클라이언트 등록 (authorization_code 고정)
	 *
	 * @param command 등록 명령
	 * @return 등록 결과
	 */
	RegisterOAuthClientResult register(RegisterOAuthClientCommand command);

	/**
	 * 인증된 회원의 셀프 SSO 클라이언트 등록 (authorization_code 고정, 1인 5개 제한)
	 *
	 * @param command 셀프 등록 명령
	 * @return 셀프 등록 결과
	 */
	SelfRegisterOAuthClientResult selfRegister(SelfRegisterOAuthClientCommand command);
}
