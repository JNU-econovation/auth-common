package com.econo.auth.api.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** JWT 쿠키 설정값 바인딩 */
@Getter
@Component
public class JwtCookieProperties {

	@Value("${auth.jwt.expiry-seconds:3600}")
	private long expirySeconds;

	@Value("${auth.jwt.cookie-name:auth_token}")
	private String cookieName;
}
