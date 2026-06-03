package com.econo.auth.api.adapter.in.web;

import com.econo.auth.api.application.ClientRedirectUriService;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult;
import com.econo.auth.api.domain.GrantType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.beans.ConstructorProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>grantType 파싱은 {@link GrantType#fromString(String)}이 담당하며, redirectUri 필수 검증은 {@link
 * RegisterOAuthClientService}가 담당한다. 컨트롤러는 HTTP 매핑과 API 키 인증에만 집중한다.
 */
@Tag(
		name = "Admin — OAuth Client Management",
		description =
				"OAuth 클라이언트 등록 및 Gateway 라우트 관리 API. "
						+ "모든 엔드포인트는 X-Internal-Api-Key 헤더 인증 필요 (서버 간 내부망 전용).")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminClientController {

	private final RegisterOAuthClientService registerOAuthClientService;
	private final ClientRedirectUriService redirectUriService;

	@Value("${AUTH_INTERNAL_API_KEY}")
	private String internalApiKey;

	/**
	 * OAuth 클라이언트 등록 요청 DTO
	 *
	 * @param grantType 그랜트 타입 (authorization_code, client_credentials). <strong>생략 가능</strong> — 생략 시
	 *     client_credentials 디폴트 적용.
	 * @param clientName 클라이언트 이름
	 * @param redirectUris 리다이렉트 URI 목록 (authorization_code 전용, null이면 빈 Set)
	 * @param upstreamUrl 업스트림 서비스 URL (선택)
	 * @param pathPrefix 경로 접두사 (선택)
	 */
	public record RegisterClientRequest(
			String grantType,
			@NotBlank String clientName,
			Set<String> redirectUris,
			String upstreamUrl,
			String pathPrefix) {

		@ConstructorProperties({"grantType", "clientName", "redirectUris", "upstreamUrl", "pathPrefix"})
		public RegisterClientRequest {
			// null → 빈 Set으로 정규화
			if (redirectUris == null) {
				redirectUris = Collections.emptySet();
			}
		}
	}

	/**
	 * OAuth 클라이언트 등록 응답 DTO
	 *
	 * @param clientId 등록된 클라이언트 ID
	 * @param clientSecret 원본 시크릿 (client_credentials 전용)
	 * @param routeId 등록된 라우트 ID (upstreamUrl 있을 때만)
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RegisterClientResponse(String clientId, String clientSecret, String routeId) {}

	/** 라우트 목록 응답 DTO */
	public record RoutesResponse(List<RouteInfo> routes) {}

	/** 라우트 정보 DTO */
	public record RouteInfo(String routeId, String clientId, String upstreamUrl, String pathPrefix) {}

	/** 에러 응답 DTO */
	public record ErrorResponse(String errorCode, String message, LocalDateTime timestamp) {
		public ErrorResponse(String errorCode, String message) {
			this(errorCode, message, LocalDateTime.now());
		}
	}

	@Operation(
			summary = "OAuth 클라이언트 등록",
			description =
					"새 OAuth 클라이언트를 등록하고 clientId를 발급한다.\n\n"
							+ "**grantType별 동작:**\n"
							+ "- `authorization_code`: PKCE 필수 공개 클라이언트. `redirectUris` 필수. clientSecret 미발급.\n"
							+ "- `client_credentials`: 비밀 클라이언트. clientSecret **1회만** 응답에 포함(이후 재조회 불가). "
							+ "`upstreamUrl` + `pathPrefix` 지정 시 Gateway 동적 라우트 자동 등록.\n\n"
							+ "grantType 생략 가능. 생략 시 `client_credentials`로 처리하여 clientSecret 발급.\n\n"
							+ "**인증:** `X-Internal-Api-Key` 헤더 필수 (서버 내부망 전용)")
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "클라이언트 등록 성공"),
		@ApiResponse(
				responseCode = "400",
				description =
						"| 코드 | 설명 |\n"
								+ "|------|------|\n"
								+ "| REDIRECT_URI_REQUIRED | authorization_code 타입이 **명시된** 경우에만 발생 |\n"
								+ "| UNSUPPORTED_GRANT_TYPE | null이 아닌 알 수 없는 값일 때만 발생 |\n"
								+ "| VALIDATION_FAILED | clientName이 빈 문자열 |",
				content = @Content),
		@ApiResponse(
				responseCode = "401",
				description = "X-Internal-Api-Key 없거나 틀림",
				content = @Content),
		@ApiResponse(
				responseCode = "409",
				description = "DUPLICATE_RESOURCE — clientName 또는 pathPrefix 중복",
				content = @Content)
	})
	@PostMapping("/clients")
	public ResponseEntity<?> registerClient(
			@Parameter(description = "내부 API 키 (환경변수 AUTH_INTERNAL_API_KEY)", required = true)
					@RequestHeader(value = "X-Internal-Api-Key", required = false)
					String apiKeyHeader,
			@Valid @RequestBody RegisterClientRequest request) {
		if (!isValidApiKey(apiKeyHeader)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(new ErrorResponse("UNAUTHORIZED", "유효하지 않은 Internal API Key입니다."));
		}

		// GrantType 파싱 + 비즈니스 검증은 도메인/서비스 레이어에 위임
		GrantType grantType = GrantType.fromString(request.grantType());
		RegisterOAuthClientCommand command =
				new RegisterOAuthClientCommand(
						grantType,
						request.clientName(),
						request.redirectUris(),
						request.upstreamUrl(),
						request.pathPrefix());

		RegisterOAuthClientResult result = registerOAuthClientService.register(command);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(
						new RegisterClientResponse(result.clientId(), result.clientSecret(), result.routeId()));
	}

	@Operation(
			summary = "Gateway 라우트 목록 조회",
			description = "등록된 service_route를 전체 반환한다. **인증:** `X-Internal-Api-Key` 헤더 필수")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "라우트 목록 반환"),
		@ApiResponse(
				responseCode = "401",
				description = "X-Internal-Api-Key 없거나 틀림",
				content = @Content)
	})
	@GetMapping("/routes")
	public ResponseEntity<?> getRoutes(
			@Parameter(description = "내부 API 키", required = true)
					@RequestHeader(value = "X-Internal-Api-Key", required = false)
					String internalApiKeyHeader) {
		if (!isValidApiKey(internalApiKeyHeader)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(new ErrorResponse("UNAUTHORIZED", "유효하지 않은 Internal API Key입니다."));
		}

		List<RouteInfo> routeInfos =
				registerOAuthClientService.getRoutes().stream()
						.map(r -> new RouteInfo(r.routeId(), r.clientId(), r.upstreamUrl(), r.pathPrefix()))
						.toList();
		return ResponseEntity.ok(new RoutesResponse(routeInfos));
	}

	// ── redirectUri 관리 ────────────────────────────────────────────

	@Operation(summary = "클라이언트 조회 (redirectUri 포함)", description = "**인증:** X-Internal-Api-Key")
	@GetMapping("/clients/{clientId}")
	public ResponseEntity<?> getClient(
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKeyHeader,
			@PathVariable String clientId) {
		if (!isValidApiKey(apiKeyHeader)) {
			return ResponseEntity.status(401)
					.body(new ErrorResponse("UNAUTHORIZED", "유효하지 않은 Internal API Key입니다."));
		}
		var client = redirectUriService.findByClientId(clientId);
		return ResponseEntity.ok(
				new ClientDetailResponse(
						client.getClientId(), client.getClientName(), client.getRedirectUris()));
	}

	@Operation(
			summary = "redirectUri 추가",
			description = "기존 redirectUri 유지하면서 새 URI를 추가한다. **인증:** X-Internal-Api-Key")
	@PostMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> addRedirectUri(
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKeyHeader,
			@PathVariable String clientId,
			@RequestBody RedirectUriRequest request) {
		if (!isValidApiKey(apiKeyHeader)) {
			return ResponseEntity.status(401)
					.body(new ErrorResponse("UNAUTHORIZED", "유효하지 않은 Internal API Key입니다."));
		}
		var updated = redirectUriService.addRedirectUri(clientId, request.uri());
		return ResponseEntity.ok(new RedirectUrisResponse(clientId, updated));
	}

	@Operation(
			summary = "redirectUri 제거",
			description = "특정 redirectUri를 삭제한다. **인증:** X-Internal-Api-Key")
	@DeleteMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> removeRedirectUri(
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKeyHeader,
			@PathVariable String clientId,
			@RequestBody RedirectUriRequest request) {
		if (!isValidApiKey(apiKeyHeader)) {
			return ResponseEntity.status(401)
					.body(new ErrorResponse("UNAUTHORIZED", "유효하지 않은 Internal API Key입니다."));
		}
		var updated = redirectUriService.removeRedirectUri(clientId, request.uri());
		return ResponseEntity.ok(new RedirectUrisResponse(clientId, updated));
	}

	@Operation(summary = "redirectUri 전체 교체", description = "**인증:** X-Internal-Api-Key")
	@PutMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> replaceRedirectUris(
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKeyHeader,
			@PathVariable String clientId,
			@RequestBody RedirectUrisReplaceRequest request) {
		if (!isValidApiKey(apiKeyHeader)) {
			return ResponseEntity.status(401)
					.body(new ErrorResponse("UNAUTHORIZED", "유효하지 않은 Internal API Key입니다."));
		}
		var updated = redirectUriService.replaceRedirectUris(clientId, request.uris());
		return ResponseEntity.ok(new RedirectUrisResponse(clientId, updated));
	}

	// ── 요청/응답 DTO ────────────────────────────────────────────

	record RedirectUriRequest(String uri) {}

	record RedirectUrisReplaceRequest(java.util.Set<String> uris) {}

	record RedirectUrisResponse(String clientId, java.util.Set<String> redirectUris) {}

	record ClientDetailResponse(
			String clientId, String clientName, java.util.Set<String> redirectUris) {}

	private boolean isValidApiKey(String header) {
		if (header == null) return false;
		return MessageDigest.isEqual(
				internalApiKey.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
	}
}
