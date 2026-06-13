package com.econo.auth.api.presentation.controller;

import com.econo.auth.api.presentation.docs.ClientApiDocs;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.SelfRegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.SelfRegisterOAuthClientResult;
import com.econo.common.auth.core.passport.Passport;
import com.econo.common.auth.web.annotation.PassportAuth;
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
@RestController
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
public class ClientController implements ClientApiDocs {

	private final RegisterOAuthClientUseCase registerOAuthClientUseCase;

	/** 셀프 등록 요청 DTO */
	public record SelfRegisterClientRequest(
			@NotBlank String clientName, @NotNull Set<String> redirectUris) {}

	/** 셀프 등록 응답 DTO */
	public record SelfRegisterClientResponse(String clientId, String clientSecret) {}

	@Override
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
