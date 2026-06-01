package com.econo.auth.infra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** JPA Auditing 활성화 설정 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {}
