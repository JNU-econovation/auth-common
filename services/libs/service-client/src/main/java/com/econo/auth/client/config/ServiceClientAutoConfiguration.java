package com.econo.auth.client.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * service-client 모듈 자동 설정
 *
 * <p>META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports에 등록되어 Spring
 * Boot 3.x AutoConfiguration으로 동작한다. auth-api의 scanBasePackages 변경 없이 com.econo.auth.client 전체를
 * 컴포넌트 스캔하고, 자기 모듈의 JPA Repository 및 Entity를 스캔한다.
 */
@AutoConfiguration
@ComponentScan("com.econo.auth.client")
@EnableJpaRepositories("com.econo.auth.client.adapter.out.persistence")
@EntityScan("com.econo.auth.client.adapter.out.persistence")
public class ServiceClientAutoConfiguration {}
