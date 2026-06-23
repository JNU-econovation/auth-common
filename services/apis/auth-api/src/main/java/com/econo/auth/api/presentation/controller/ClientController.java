package com.econo.auth.api.presentation.controller;

import com.econo.auth.api.presentation.docs.ClientApiDocs;
import com.econo.auth.api.presentation.dto.MyClientItemResponse;
import com.econo.auth.api.presentation.dto.MyClientListResponse;
import com.econo.auth.api.presentation.dto.MyClientRouteInfo;
import com.econo.auth.api.presentation.dto.SelfRegisterClientRequest;
import com.econo.auth.api.presentation.dto.SelfRegisterClientResponse;
import com.econo.auth.api.presentation.dto.UpdateMyClientRequest;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase.DeleteMyClientCommand;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase.MyClientResult;
import com.econo.auth.client.application.usecase.ManageOwnClientUseCase.UpdateMyClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.SelfRegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase.SelfRegisterOAuthClientResult;
import com.econo.common.auth.core.passport.Passport;
import com.econo.common.auth.web.annotation.PassportAuth;
import jakarta.validation.Valid;
import java.util.List;
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
 * OAuth 클라이언트 셀프 등록·관리 REST 컨트롤러
 *
 * <p>인증된 에코노 회원 누구나 SSO 클라이언트를 직접 등록·조회·수정·삭제할 수 있다. Gateway가 주입하는 X-User-Passport 헤더에서 Passport를
 * 추출하여 인증을 수행한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
public class ClientController implements ClientApiDocs {

	private final RegisterOAuthClientUseCase registerOAuthClientUseCase;
	private final ManageOwnClientUseCase manageOwnClientUseCase;

	@Override
	@PostMapping
	public ResponseEntity<?> registerClient(
			@PassportAuth Passport passport, @Valid @RequestBody SelfRegisterClientRequest request) {
		SelfRegisterOAuthClientCommand command =
				new SelfRegisterOAuthClientCommand(
						request.clientName(),
						request.redirectUris(),
						passport.getMemberId(),
						request.pathPrefix(),
						request.upstreamUrl());
		SelfRegisterOAuthClientResult result = registerOAuthClientUseCase.selfRegister(command);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(
						new SelfRegisterClientResponse(
								result.clientId(),
								result.clientSecret(),
								result.routeId(),
								result.pathPrefix(),
								result.upstreamUrl(),
								result.enabled()));
	}

	@Override
	@GetMapping
	public ResponseEntity<MyClientListResponse> listMyClients(@PassportAuth Passport passport) {
		List<MyClientResult> results = manageOwnClientUseCase.listMyClients(passport.getMemberId());
		List<MyClientItemResponse> items = results.stream().map(this::toItemResponse).toList();
		return ResponseEntity.ok(new MyClientListResponse(items));
	}

	@Override
	@GetMapping("/{clientId}")
	public ResponseEntity<MyClientItemResponse> getMyClient(
			@PassportAuth Passport passport, @PathVariable String clientId) {
		MyClientResult result = manageOwnClientUseCase.getMyClient(clientId, passport.getMemberId());
		return ResponseEntity.ok(toItemResponse(result));
	}

	@Override
	@PutMapping("/{clientId}")
	public ResponseEntity<MyClientItemResponse> updateMyClient(
			@PassportAuth Passport passport,
			@PathVariable String clientId,
			@Valid @RequestBody UpdateMyClientRequest request) {
		UpdateMyClientCommand command =
				new UpdateMyClientCommand(
						clientId,
						passport.getMemberId(),
						request.clientName(),
						request.redirectUris(),
						request.pathPrefix(),
						request.upstreamUrl());
		MyClientResult result = manageOwnClientUseCase.updateMyClient(command);
		return ResponseEntity.ok(toItemResponse(result));
	}

	@Override
	@DeleteMapping("/{clientId}")
	public ResponseEntity<Void> deleteMyClient(
			@PassportAuth Passport passport, @PathVariable String clientId) {
		manageOwnClientUseCase.deleteMyClient(
				new DeleteMyClientCommand(clientId, passport.getMemberId()));
		return ResponseEntity.noContent().build();
	}

	private MyClientItemResponse toItemResponse(MyClientResult result) {
		MyClientRouteInfo route =
				result.routeId() != null
						? new MyClientRouteInfo(
								result.routeId(), result.pathPrefix(), result.upstreamUrl(), result.routeEnabled())
						: null;
		return new MyClientItemResponse(
				result.clientId(), result.clientName(), result.redirectUris(), route);
	}
}
