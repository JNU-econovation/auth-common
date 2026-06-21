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
	 * @param pathPrefix Gateway 라우트 경로 접두사 (null 허용 — upstreamUrl과 쌍으로 제공 시 라우트 생성)
	 * @param upstreamUrl 업스트림 서비스 URL (null 허용 — pathPrefix와 쌍으로 제공 시 라우트 생성)
	 */
	record SelfRegisterOAuthClientCommand(
			String clientName,
			Set<String> redirectUris,
			Long ownerId,
			String pathPrefix,
			String upstreamUrl) {}

	/**
	 * 셀프 등록 OAuth 클라이언트 결과
	 *
	 * @param clientId 등록된 클라이언트 ID
	 * @param clientSecret 1회 노출 평문 clientSecret
	 * @param routeId 생성된 라우트 ID (UUID) — 라우트 미생성 시 null
	 * @param pathPrefix 등록된 경로 접두사 — 라우트 미생성 시 null
	 * @param upstreamUrl 업스트림 서비스 URL — 라우트 미생성 시 null
	 * @param enabled 라우트 활성화 여부 — 라우트 미생성 시 null
	 */
	record SelfRegisterOAuthClientResult(
			String clientId,
			String clientSecret,
			String routeId,
			String pathPrefix,
			String upstreamUrl,
			Boolean enabled) {}

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
