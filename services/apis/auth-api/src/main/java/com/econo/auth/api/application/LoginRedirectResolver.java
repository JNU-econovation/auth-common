package com.econo.auth.api.application;

import com.econo.auth.client.application.usecase.ClientRedirectUriService;
import com.econo.auth.client.application.usecase.ClientRedirectUriService.ClientInfo;
import com.econo.auth.client.exception.InvalidClientException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * clientId 기반 redirect_uri 결정 서비스 (Application Layer)
 *
 * <p>{@link ClientRedirectUriService#findByClientId(String)}로 등록된 redirect_uri Set을 조회하여 결정적으로 첫 번째
 * URI를 반환한다. clientId가 null·blank이거나 {@link InvalidClientException}이 발생하거나 redirectUris가 비어 있으면
 * {@code defaultUrl}을 반환한다.
 *
 * <p>open redirect 방어 원칙에 따라 user-supplied URL을 사용하지 않으므로 open redirect가 구조적으로 불가능하다.
 */
@Slf4j
@RequiredArgsConstructor
public class LoginRedirectResolver {

	private final ClientRedirectUriService clientRedirectUriService;

	/**
	 * clientId에 등록된 redirect_uri를 조회하여 최종 리다이렉트 목적지 URL을 결정한다.
	 *
	 * <ul>
	 *   <li>{@code clientId}가 null이거나 blank이면 즉시 {@code defaultUrl} 반환
	 *   <li>{@link InvalidClientException} 발생(미등록 clientId)이면 {@code defaultUrl} 반환
	 *   <li>그 외 예상치 못한 예외(예: 인프라/DB 오류)가 발생해도 {@code defaultUrl} 반환 (fail-safe)
	 *   <li>{@link ClientInfo#redirectUris()}가 비어 있으면 {@code defaultUrl} 반환
	 *   <li>redirect_uri가 1개이면 그것을 반환
	 *   <li>redirect_uri가 여러 개이면 알파벳 오름차순 정렬 후 첫 번째 반환 (SAS Set 순서 비보장 한계 반영)
	 * </ul>
	 *
	 * @param clientId OAuth 클라이언트 ID (null·blank 허용)
	 * @param defaultUrl clientId 미전달·미등록·redirect_uri 없음 시 사용할 안전한 기본 URL
	 * @return 결정된 리다이렉트 목적지 URL
	 */
	public String resolve(String clientId, String defaultUrl) {
		if (clientId == null || clientId.isBlank()) {
			return defaultUrl;
		}
		try {
			ClientInfo clientInfo = clientRedirectUriService.findByClientId(clientId);
			Set<String> redirectUris = clientInfo.redirectUris();
			if (redirectUris == null || redirectUris.isEmpty()) {
				return defaultUrl;
			}
			return redirectUris.stream().sorted().findFirst().orElse(defaultUrl);
		} catch (InvalidClientException e) {
			log.debug("clientId={} not registered, fallback to defaultUrl", clientId);
			return defaultUrl;
		} catch (Exception e) {
			log.warn(
					"Unexpected error resolving redirect for clientId={}, fallback to defaultUrl: {}",
					clientId,
					e.getMessage());
			return defaultUrl;
		}
	}
}
