package com.econo.auth.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Auth API Spring Boot 애플리케이션 진입점 */
@SpringBootApplication(
		scanBasePackages = {"com.econo.auth.api", "com.econo.auth.core", "com.econo.auth.infra"})
public class AuthApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthApiApplication.class, args);
	}
}
