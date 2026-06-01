package com.econo.auth.infra.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** JPA Repository 및 Entity 스캔 설정 */
@Configuration
@EnableJpaRepositories(
		basePackages = {"com.econo.auth.infra", "com.econo.auth.api.adapter.out.persistence"})
@EntityScan(basePackages = {"com.econo.auth.infra", "com.econo.auth.api.adapter.out.persistence"})
public class InfraConfig {}
