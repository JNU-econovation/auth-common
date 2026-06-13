package com.econo.auth.api.config;

import com.econo.auth.api.application.service.GatewayRefreshClientImpl;
import com.econo.auth.client.application.service.GatewayRefreshClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * GatewayRefreshClient 빈 등록 설정
 *
 * <p>{@code GATEWAY_URI} 환경변수로 api-gateway 주소를 바인딩하고, {@code GATEWAY_INTERNAL_SECRET}으로 내부 시크릿을
 * 바인딩한다.
 */
@Configuration
public class GatewayClientConfig {

	/**
	 * GatewayRefreshClient 빈 등록
	 *
	 * @param gatewayUri api-gateway 주소 (GATEWAY_URI 환경변수)
	 * @param internalSecret 내부 시크릿 (GATEWAY_INTERNAL_SECRET 환경변수)
	 * @return GatewayRefreshClient 인스턴스
	 */
	@Bean
	public GatewayRefreshClient gatewayRefreshClient(
			@Value("${GATEWAY_URI:http://localhost:8080}") String gatewayUri,
			@Value("${GATEWAY_INTERNAL_SECRET:dev-secret}") String internalSecret) {
		RestClient restClient = RestClient.builder().baseUrl(gatewayUri).build();
		return new GatewayRefreshClientImpl(restClient, internalSecret);
	}
}
