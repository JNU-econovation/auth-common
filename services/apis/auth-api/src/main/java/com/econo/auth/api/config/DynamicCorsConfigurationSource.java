package com.econo.auth.api.config;

import com.econo.auth.client.application.port.out.ServiceClientRepository;
import com.econo.auth.client.application.usecase.ClientRedirectUriService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 동적 CORS 설정 소스
 *
 * <p>SAS {@link RegisteredClientRepository}의 모든 클라이언트 redirectUri에서 오리진을 추출하여 CORS 허용 오리진에 추가한다. 별도
 * env 설정 없이 클라이언트 등록만으로 CORS가 자동으로 허용된다.
 *
 * <p>fallback: {@code CORS_ALLOWED_ORIGINS} 환경변수 오리진은 항상 포함.
 */
@Component
@RequiredArgsConstructor
public class DynamicCorsConfigurationSource implements CorsConfigurationSource {

	private final RegisteredClientRepository registeredClientRepository;
	private final ServiceClientRepository serviceClientRepository;

	@Value("${CORS_ALLOWED_ORIGINS:http://localhost:3000}")
	private String staticAllowedOrigins;

	@Override
	public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
		Set<String> allowedOrigins = collectAllowedOrigins();

		CorsConfiguration config = new CorsConfiguration();
		allowedOrigins.forEach(config::addAllowedOrigin);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		return config;
	}

	/** env 오리진 + 등록된 모든 클라이언트 redirectUri 오리진 합산 */
	private Set<String> collectAllowedOrigins() {
		Set<String> origins = new HashSet<>();

		// 1. env 고정 오리진
		for (String origin : staticAllowedOrigins.split(",")) {
			origins.add(origin.trim());
		}

		// 2. 등록된 클라이언트의 redirectUri 오리진 자동 추출
		// JdbcRegisteredClientRepository는 전체 목록 조회 API가 없으므로
		// service_client 테이블의 registered_client_id로 조회
		try {
			extractFromKnownClients(origins);
		} catch (Exception ignored) {
			// DB 조회 실패 시 env 오리진만 사용
		}

		return origins;
	}

	/** service_client의 registered_client_id → SAS repo → redirectUri → 오리진 추출 */
	private void extractFromKnownClients(Set<String> origins) {
		serviceClientRepository
				.findAllRegisteredClientIds()
				.forEach(
						registeredClientId -> {
							var client = registeredClientRepository.findById(registeredClientId);
							if (client != null) {
								client
										.getRedirectUris()
										.forEach(
												uri -> {
													String origin = ClientRedirectUriService.extractOrigin(uri);
													if (origin != null) origins.add(origin);
												});
							}
						});
	}
}
