package com.econo.auth.api.adapter.in.web;

import com.econo.auth.client.application.usecase.ClientRedirectUriService;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 클라이언트 관리 REST 컨트롤러 (Admin)
 *
 * <p>Gateway가 JWT를 검증하고 X-User-Passport 헤더를 주입한다. 컨트롤러는 Passport의 ADMIN 역할 여부만 확인한다.
 */
@Slf4j
@Tag(
		name = "Admin — OAuth Client Management",
		description = "OAuth 클라이언트 등록 및 관리 API. ADMIN 역할 필요 (Gateway JWT 인증 후 X-User-Passport 주입).")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminClientController {

	private static final String PASSPORT_HEADER = "X-User-Passport";

	private final RegisterOAuthClientService registerOAuthClientService;
	private final ClientRedirectUriService redirectUriService;
	private final ObjectMapper objectMapper;

	public record RegisterClientRequest(@NotBlank String clientName, Set<String> redirectUris) {}

	public record RegisterClientResponse(String clientId) {}

	public record ErrorResponse(String errorCode, String message, LocalDateTime timestamp) {
		public ErrorResponse(String errorCode, String message) {
			this(errorCode, message, LocalDateTime.now());
		}
	}

	@Operation(
			summary = "OAuth 클라이언트 등록",
			description =
					"새 OAuth 클라이언트(프론트엔드/모바일)를 등록하고 clientId를 발급한다.\n\n"
							+ "항상 authorization_code (PKCE) 클라이언트로 등록된다. `redirectUris` 필수.",
			security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "클라이언트 등록 성공"),
		@ApiResponse(
				responseCode = "400",
				description =
						"| 코드 | 설명 |\n"
								+ "|------|------|\n"
								+ "| REDIRECT_URI_REQUIRED | redirectUris가 없음 |\n"
								+ "| VALIDATION_FAILED | clientName이 빈 문자열 |",
				content = @Content),
		@ApiResponse(responseCode = "403", description = "ADMIN 역할 없음", content = @Content),
		@ApiResponse(responseCode = "409", description = "DUPLICATE_CLIENT_NAME", content = @Content)
	})
	@PostMapping("/clients")
	public ResponseEntity<?> registerClient(
			@RequestHeader(value = PASSPORT_HEADER, required = false) String passportHeader,
			@Valid @RequestBody RegisterClientRequest request) {
		if (!isAdmin(passportHeader)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(new ErrorResponse("FORBIDDEN", "관리자 권한이 필요합니다."));
		}

		RegisterOAuthClientCommand command =
				new RegisterOAuthClientCommand(request.clientName(), request.redirectUris());
		RegisterOAuthClientResult result = registerOAuthClientService.register(command);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new RegisterClientResponse(result.clientId()));
	}

	@Operation(
			summary = "클라이언트 조회 (redirectUri 포함)",
			security = @SecurityRequirement(name = "bearerAuth"))
	@GetMapping("/clients/{clientId}")
	public ResponseEntity<?> getClient(
			@RequestHeader(value = PASSPORT_HEADER, required = false) String passportHeader,
			@PathVariable String clientId) {
		if (!isAdmin(passportHeader)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(new ErrorResponse("FORBIDDEN", "관리자 권한이 필요합니다."));
		}
		var client = redirectUriService.findByClientId(clientId);
		return ResponseEntity.ok(
				new ClientDetailResponse(client.clientId(), client.clientName(), client.redirectUris()));
	}

	@Operation(
			summary = "redirectUri 추가",
			description = "기존 redirectUri 유지하면서 새 URI를 추가한다.",
			security = @SecurityRequirement(name = "bearerAuth"))
	@PostMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> addRedirectUri(
			@RequestHeader(value = PASSPORT_HEADER, required = false) String passportHeader,
			@PathVariable String clientId,
			@RequestBody RedirectUriRequest request) {
		if (!isAdmin(passportHeader)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(new ErrorResponse("FORBIDDEN", "관리자 권한이 필요합니다."));
		}
		var updated = redirectUriService.addRedirectUri(clientId, request.uri());
		return ResponseEntity.ok(new RedirectUrisResponse(clientId, updated));
	}

	@Operation(summary = "redirectUri 제거", security = @SecurityRequirement(name = "bearerAuth"))
	@DeleteMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> removeRedirectUri(
			@RequestHeader(value = PASSPORT_HEADER, required = false) String passportHeader,
			@PathVariable String clientId,
			@RequestBody RedirectUriRequest request) {
		if (!isAdmin(passportHeader)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(new ErrorResponse("FORBIDDEN", "관리자 권한이 필요합니다."));
		}
		var updated = redirectUriService.removeRedirectUri(clientId, request.uri());
		return ResponseEntity.ok(new RedirectUrisResponse(clientId, updated));
	}

	@Operation(summary = "redirectUri 전체 교체", security = @SecurityRequirement(name = "bearerAuth"))
	@PutMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> replaceRedirectUris(
			@RequestHeader(value = PASSPORT_HEADER, required = false) String passportHeader,
			@PathVariable String clientId,
			@RequestBody RedirectUrisReplaceRequest request) {
		if (!isAdmin(passportHeader)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(new ErrorResponse("FORBIDDEN", "관리자 권한이 필요합니다."));
		}
		var updated = redirectUriService.replaceRedirectUris(clientId, request.uris());
		return ResponseEntity.ok(new RedirectUrisResponse(clientId, updated));
	}

	// ── DTO ────────────────────────────────────────────────────

	record RedirectUriRequest(String uri) {}

	record RedirectUrisReplaceRequest(Set<String> uris) {}

	record RedirectUrisResponse(String clientId, Set<String> redirectUris) {}

	record ClientDetailResponse(String clientId, String clientName, Set<String> redirectUris) {}

	// auth-api 전체에서 공유하는 Passport 파싱 유틸
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PassportClaims(Long memberId, List<String> roles) {

		/** ADMIN 또는 SUPER_ADMIN이면 true */
		public boolean isAdmin() {
			return roles != null && (roles.contains("ADMIN") || roles.contains("SUPER_ADMIN"));
		}

		public boolean isSuperAdmin() {
			return roles != null && roles.contains("SUPER_ADMIN");
		}
	}

	private boolean isAdmin(String passportHeader) {
		if (passportHeader == null || passportHeader.isBlank()) return false;
		try {
			String json = new String(Base64.getDecoder().decode(passportHeader), StandardCharsets.UTF_8);
			PassportClaims claims = objectMapper.readValue(json, PassportClaims.class);
			return claims.isAdmin();
		} catch (Exception e) {
			log.warn("X-User-Passport 파싱 실패: {}", e.getMessage());
			return false;
		}
	}
}
