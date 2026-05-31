package com.econo.auth.member.infra.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** JPA Repository 및 Entity 스캔 설정 */
@Configuration
@EnableJpaRepositories(basePackages = "com.econo.auth.member.infra")
@EntityScan(basePackages = "com.econo.auth.member.infra")
public class InfraConfig {}
