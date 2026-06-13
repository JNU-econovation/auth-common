package com.econo.auth.login.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * login 모듈 자동 설정
 *
 * <p>META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports에 등록되어 Spring
 * Boot 3.x AutoConfiguration으로 동작한다. auth-api의 scanBasePackages 변경 없이 com.econo.auth.login 전체를 컴포넌트
 * 스캔한다. JPA 엔티티가 없으므로 {@code @EnableJpaRepositories}, {@code @EntityScan} 없음.
 */
@AutoConfiguration
@ComponentScan("com.econo.auth.login")
public class LoginAutoConfiguration {}
