package com.econo.auth.api.application.port.out;

import java.util.Set;

/**
 * SAS(Spring Authorization Server) 클라이언트 등록 아웃바운드 포트
 *
 * <p>Application 계층이 SAS 인프라 타입({@code RegisteredClient})에 직접 의존하지 않도록 격리한다. SAS 관련 코드는 구현체( {@code
 * SasClientRegistrarAdapter})에만 존재한다.
 */
public interface SasClientRegistrar {

	/**
	 * Authorization Code 클라이언트 등록
	 *
	 * @param clientId 클라이언트 ID (UUID)
	 * @param clientName 클라이언트 이름
	 * @param redirectUris 허용 리다이렉트 URI 목록
	 */
	void registerAuthorizationCodeClient(
			String clientId, String clientName, Set<String> redirectUris);

	/**
	 * Client Credentials 클라이언트 등록
	 *
	 * @param clientId 클라이언트 ID (UUID)
	 * @param clientName 클라이언트 이름
	 * @param bcryptHashedSecret BCrypt 해시된 시크릿 ({@code {bcrypt}...} 형식)
	 */
	void registerClientCredentialsClient(
			String clientId, String clientName, String bcryptHashedSecret);
}
