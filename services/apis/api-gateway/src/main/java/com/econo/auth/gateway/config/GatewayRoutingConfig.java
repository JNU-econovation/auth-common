package com.econo.auth.gateway.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Cloud Gateway 정적 라우팅 설정
 *
 * <p>라우트는 환경변수({@code AUTH_API_URI}, {@code EEOS_API_URI})로 외부화된다. 새 서비스 추가 시 이 파일에 라우트를 추가하고
 * 재배포한다.
 *
 * <p>인증 불필요 경로({@link #permittedPaths})는 {@code application.yml}의 {@code gateway.permitted-paths}에서
 * 로드되며, {@link com.econo.auth.gateway.filter.BearerToPassportFilter}가 참조한다.
 */
@Configuration
@ConfigurationProperties(prefix = "gateway")
public class GatewayRoutingConfig {

	@Value("${AUTH_API_URI:http://localhost:8081}")
	private String authApiUri;

	@Value("${EEOS_API_URI:http://localhost:8080}")
	private String eeosApiUri;

	/** application.yml의 gateway.permitted-paths에서 주입 */
	private List<String> permittedPaths = List.of();

	public List<String> getPermittedPaths() {
		return permittedPaths;
	}

	public void setPermittedPaths(List<String> permittedPaths) {
		this.permittedPaths = permittedPaths != null ? permittedPaths : List.of();
	}

	/**
	 * BearerToPassportFilter에서 참조하는 인증 불필요 경로 목록.
	 *
	 * <p>yml에서 로드되므로 재배포 없이 경로 추가/제거 가능.
	 */
	public List<String> permittedPaths() {
		return permittedPaths;
	}

	/**
	 * Gateway 라우트 정의.
	 *
	 * <p><strong>주의:</strong> {@code /api/v1/clients/**}, {@code /api/v1/routes/**} 라우트를 이 파일에 추가하지 말
	 * 것. 클라이언트 관리 endpoint는 내부망 전용이며, Gateway 외부 라우트에 노출 시 public 등록 endpoint ({@code POST
	 * /api/v1/clients})에 대한 도배(flood) 공격 위험이 있다. ADR-0010 참조.
	 */
	@Bean
	public RouteLocator routes(RouteLocatorBuilder builder) {
		return builder
				.routes()
				.route("auth-api", r -> r.path("/api/v1/auth/**").uri(authApiUri))
				.route("auth-members", r -> r.path("/api/v1/members/**").uri(authApiUri))
				.route("sas-oauth2", r -> r.path("/oauth2/**").uri(authApiUri))
				.route("sas-well-known", r -> r.path("/.well-known/**").uri(authApiUri))
				.route("sas-userinfo", r -> r.path("/userinfo").uri(authApiUri))
				.route(
						"eeos",
						r ->
								r.path("/api/**")
										.filters(f -> f.removeRequestHeader("Authorization"))
										.uri(eeosApiUri))
				.build();
	}
}
