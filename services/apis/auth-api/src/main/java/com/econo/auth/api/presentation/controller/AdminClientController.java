package com.econo.auth.api.presentation.controller;

import com.econo.auth.api.presentation.docs.AdminClientApiDocs;
import com.econo.auth.api.presentation.dto.ClientDetailResponse;
import com.econo.auth.api.presentation.dto.RedirectUriRequest;
import com.econo.auth.api.presentation.dto.RedirectUrisReplaceRequest;
import com.econo.auth.api.presentation.dto.RedirectUrisResponse;
import com.econo.auth.api.presentation.dto.RegisterClientRequest;
import com.econo.auth.api.presentation.dto.RegisterClientResponse;
import com.econo.auth.client.application.usecase.ClientRedirectUriUseCase;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.RegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.RegisterOAuthClientResult;
import com.econo.common.auth.core.passport.Passport;
import com.econo.common.auth.core.passport.Roles;
import com.econo.common.auth.web.annotation.PassportAuth;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 클라이언트 관리 REST 컨트롤러 (Admin)
 *
 * <p>Gateway가 JWT를 검증하고 X-User-Passport 헤더를 주입한다. ADMIN 또는 SUPER_ADMIN 역할이 필요하다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminClientController implements AdminClientApiDocs {

	private final RegisterOAuthClientUseCase registerOAuthClientUseCase;
	private final ClientRedirectUriUseCase redirectUriUseCase;

	@Override
	@PostMapping("/clients")
	public ResponseEntity<?> registerClient(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@Valid @RequestBody RegisterClientRequest request) {
		RegisterOAuthClientCommand command =
				new RegisterOAuthClientCommand(request.clientName(), request.redirectUris());
		RegisterOAuthClientResult result = registerOAuthClientUseCase.register(command);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new RegisterClientResponse(result.clientId()));
	}

	@Override
	@GetMapping("/clients/{clientId}")
	public ResponseEntity<?> getClient(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@PathVariable String clientId) {
		var client = redirectUriUseCase.findByClientId(clientId);
		return ResponseEntity.ok(
				new ClientDetailResponse(client.clientId(), client.clientName(), client.redirectUris()));
	}

	@Override
	@PostMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> addRedirectUri(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@PathVariable String clientId,
			@Valid @RequestBody RedirectUriRequest request) {
		var updated = redirectUriUseCase.addRedirectUri(clientId, request.uri());
		return ResponseEntity.ok(new RedirectUrisResponse(clientId, updated));
	}

	@Override
	@DeleteMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> removeRedirectUri(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@PathVariable String clientId,
			@Valid @RequestBody RedirectUriRequest request) {
		var updated = redirectUriUseCase.removeRedirectUri(clientId, request.uri());
		return ResponseEntity.ok(new RedirectUrisResponse(clientId, updated));
	}

	@Override
	@PutMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> replaceRedirectUris(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@PathVariable String clientId,
			@Valid @RequestBody RedirectUrisReplaceRequest request) {
		var updated = redirectUriUseCase.replaceRedirectUris(clientId, request.uris());
		return ResponseEntity.ok(new RedirectUrisResponse(clientId, updated));
	}
}
