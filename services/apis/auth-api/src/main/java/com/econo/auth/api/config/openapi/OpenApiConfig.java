package com.econo.auth.api.config.openapi;

import com.econo.common.auth.core.passport.Passport;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI(Swagger) 전역 정의 — API 메타데이터, 태그, 보안 스킴을 한곳에서 선언한다.
 *
 * <p>태그는 도메인명 한 단어로 통일하며, 설명·순서는 여기서 단일 소스로 관리한다. 인증이 필요한 엔드포인트는 {@code cookieAuth} 스킴을 참조한다(로그인 시
 * 발급되는 HttpOnly 쿠키 {@code at}로 토큰을 전달하며 브라우저가 자동 전송한다. Gateway가 검증 후 내부 서비스에 X-User-Passport를
 * 주입한다).
 */
@Configuration
@OpenAPIDefinition(
		info =
				@Info(
						title = "ECONO Auth API",
						description = "ECONO 인증 서버 — 회원 인증, OAuth 클라이언트, 관리 API",
						version = "v1"),
		tags = {
			@Tag(name = "Auth", description = "회원 인증 API (가입·로그인·토큰 재발급·로그아웃)"),
			@Tag(name = "Member", description = "회원 정보 조회 API"),
			@Tag(name = "Client", description = "SSO 클라이언트 셀프 등록 API"),
			@Tag(name = "Admin", description = "관리자 전용 API (회원·역할·OAuth 클라이언트·동적 라우트 관리)"),
			@Tag(name = "Health", description = "헬스체크 API")
		})
@SecurityScheme(
		name = "cookieAuth",
		type = SecuritySchemeType.APIKEY,
		in = SecuritySchemeIn.COOKIE,
		paramName = "at",
		description = "로그인 시 발급되는 Access Token. HttpOnly 쿠키 `at`로 전달하며 브라우저가 자동 전송한다.")
public class OpenApiConfig {

	/** Passport 는 클라이언트 입력이 아니라 Gateway가 X-User-Passport 로 주입하는 값이므로 API 명세의 파라미터로 노출하지 않는다. */
	@PostConstruct
	void ignorePassportParameter() {
		SpringDocUtils.getConfig().addRequestWrapperToIgnore(Passport.class);
	}
}
