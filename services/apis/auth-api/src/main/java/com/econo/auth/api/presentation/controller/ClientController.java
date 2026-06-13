package com.econo.auth.api.presentation.controller;

import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.SelfRegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.SelfRegisterOAuthClientResult;
import com.econo.common.auth.core.passport.Passport;
import com.econo.common.auth.web.annotation.PassportAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 클라이언트 셀프 등록 REST 컨트롤러
 *
 * <p>인증된 에코노 회원 누구나 SSO 클라이언트를 직접 등록할 수 있다. Gateway가 주입하는 X-User-Passport 헤더에서 Passport를 추출하여 인증을
 * 수행한다. ADMIN 역할 없이 인증된 회원이면 모두 허용된다. 회원당 최대 5개 제한이 적용된다.
 */
@Slf4j
@Tag(name = "OAuth Client — Self Registration", description = "인증된 에코노 회원의 SSO 클라이언트 셀프 등록 API.")
@RestController
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
public class ClientController {

	private final RegisterOAuthClientUseCase registerOAuthClientUseCase;

	/** 셀프 등록 요청 DTO */
	public record SelfRegisterClientRequest(
			@NotBlank String clientName, @NotNull Set<String> redirectUris) {}

	/** 셀프 등록 응답 DTO */
	public record SelfRegisterClientResponse(String clientId, String clientSecret) {}

	@Operation(
			summary = "OAuth 클라이언트 셀프 등록",
			description =
					"인증된 회원이 자신의 서비스 앱을 SSO 클라이언트로 등록한다.\n\n"
							+ "항상 authorization_code (PKCE) 클라이언트로 등록된다. `redirectUris` 필수. 회원당 최대 5개 제한.")
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "클라이언트 등록 성공 — clientId + clientSecret 1회 반환"),
		@ApiResponse(
				responseCode = "401",
				description = "X-User-Passport 헤더 누락 또는 파싱 실패",
				content = @Content),
		@ApiResponse(
				responseCode = "400",
				description =
						"| 코드 | 설명 |\n"
								+ "|------|------|\n"
								+ "| REDIRECT_URI_REQUIRED | redirectUris가 없음 |\n"
								+ "| VALIDATION_FAILED | clientName이 빈 문자열 |",
				content = @Content),
		@ApiResponse(responseCode = "409", description = "DUPLICATE_CLIENT_NAME", content = @Content),
		@ApiResponse(
				responseCode = "422",
				description = "CLIENT_LIMIT_EXCEEDED — 회원당 최대 5개 초과",
				content = @Content)
	})
	@PostMapping
	public ResponseEntity<?> registerClient(
			@PassportAuth Passport passport, @Valid @RequestBody SelfRegisterClientRequest request) {
		SelfRegisterOAuthClientCommand command =
				new SelfRegisterOAuthClientCommand(
						request.clientName(), request.redirectUris(), passport.getMemberId());
		SelfRegisterOAuthClientResult result = registerOAuthClientUseCase.selfRegister(command);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new SelfRegisterClientResponse(result.clientId(), result.clientSecret()));
	}
}
