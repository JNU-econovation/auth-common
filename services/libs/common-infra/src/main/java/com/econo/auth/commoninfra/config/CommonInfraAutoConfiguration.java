package com.econo.auth.commoninfra.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * common-infra 모듈 자동 설정 — JPA Auditing 활성화
 *
 * <p>META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports에 등록되어 Spring
 * Boot 3.x AutoConfiguration으로 동작한다. {@code @EnableJpaAuditing}을 직접 선언하여 JPA Auditing을 활성화한다. 이 클래스
 * 하나로 모든 모듈의 {@code @EnableJpaAuditing} 의존을 일원화한다.
 */
@AutoConfiguration
@EnableJpaAuditing
public class CommonInfraAutoConfiguration {}
