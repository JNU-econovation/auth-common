package com.econo.common.auth.config;

import com.econo.common.auth.web.resolver.PassportArgumentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Auth 관련 자동 설정
 *
 * <p>이 설정이 포함된 서비스에서는 자동으로 {@code @PassportAuth} 어노테이션을 사용할 수 있음
 */
@Configuration
public class AuthAutoConfiguration implements WebMvcConfigurer {

	private final PassportArgumentResolver passportArgumentResolver;

	public AuthAutoConfiguration(ObjectMapper objectMapper) {
		this.passportArgumentResolver = new PassportArgumentResolver(objectMapper);
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this.passportArgumentResolver);
	}
}
