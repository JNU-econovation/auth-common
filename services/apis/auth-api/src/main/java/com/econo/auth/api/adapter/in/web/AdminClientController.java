package com.econo.auth.api.adapter.in.web;

import com.econo.auth.client.application.usecase.ClientRedirectUriService;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand;
import com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult;
import com.econo.auth.client.domain.GrantType;
import com.econo.auth.client.exception.UnsupportedGrantTypeException;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.beans.ConstructorProperties;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
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
 * RegisterOAuthClientService}가 담당한다. 컨트롤러는 HTTP 매핑과 인증(public/Basic Auth)에만 집중한다.
 */
@Tag(
		name = "Admin — OAuth Client Management",
		description =
				"OAuth 클라이언트 등록 및 Gateway 라우트 관리 API. "
						+ "등록(POST /clients) 및 라우트 조회(GET /routes)는 인증 불필요 (public). "
						+ "클라이언트 조회 및 redirectUri 관리(4개 엔드포인트)는 "
						+ "Authorization: Basic base64(clientId:clientSecret) 헤더 필수 (서버 내부망 전용).")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AdminClientController {

	private final RegisterOAuthClientService registerOAuthClientService;
	private final ClientRedirectUriService redirectUriService;
	private final RegisteredClientRepository registeredClientRepository;
	private final PasswordEncoder passwordEncoder;

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
							+ "**인증:** 불필요 (public)")
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
				responseCode = "409",
				description = "DUPLICATE_RESOURCE — clientName 또는 pathPrefix 중복",
				content = @Content)
	})
	@PostMapping("/clients")
	public ResponseEntity<?> registerClient(@Valid @RequestBody RegisterClientRequest request) {
		// GrantType 파싱 — IllegalArgumentException을 어댑터 계층 예외로 변환
		GrantType grantType;
		try {
			grantType = GrantType.fromString(request.grantType());
		} catch (IllegalArgumentException ex) {
			throw new UnsupportedGrantTypeException(request.grantType());
		}
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
			description = "등록된 service_route를 전체 반환한다. **인증:** 불필요 (public)")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "라우트 목록 반환"),
	})
	@GetMapping("/routes")
	public ResponseEntity<?> getRoutes() {
		List<RouteInfo> routeInfos =
				registerOAuthClientService.getRoutes().stream()
						.map(r -> new RouteInfo(r.routeId(), r.clientId(), r.upstreamUrl(), r.pathPrefix()))
						.toList();
		return ResponseEntity.ok(new RoutesResponse(routeInfos));
	}

	// ── redirectUri 관리 ────────────────────────────────────────────

	@Operation(
			summary = "클라이언트 조회 (redirectUri 포함)",
			description =
					"**인증:** `Authorization: Basic base64(clientId:clientSecret)` 필수."
							+ " path clientId와 Basic Auth clientId가 일치해야 함.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "클라이언트 조회 성공"),
		@ApiResponse(
				responseCode = "401",
				description =
						"`INVALID_CLIENT_CREDENTIALS` — Authorization 헤더 누락, 디코딩 실패, clientId 미존재, BCrypt 불일치",
				content = @Content),
		@ApiResponse(
				responseCode = "403",
				description = "`FORBIDDEN_CLIENT_MISMATCH` — path clientId ≠ Basic Auth clientId",
				content = @Content)
	})
	@GetMapping("/clients/{clientId}")
	public ResponseEntity<?> getClient(
			@RequestHeader(value = "Authorization", required = false) String authHeader,
			@PathVariable String clientId) {
		AuthResult auth = verifyBasicAuth(authHeader);
		if (auth.isFailure()) return auth.errorResponse();

		if (!clientId.equals(auth.clientId())) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(
							new ErrorResponse(
									"FORBIDDEN_CLIENT_MISMATCH", "경로의 clientId와 인증 clientId가 일치하지 않습니다."));
		}

		ClientRedirectUriService.ClientInfo client = redirectUriService.findByClientId(clientId);
		return ResponseEntity.ok(
				new ClientDetailResponse(client.clientId(), client.clientName(), client.redirectUris()));
	}

	@Operation(
			summary = "redirectUri 추가",
			description =
					"기존 redirectUri 유지하면서 새 URI를 추가한다."
							+ " **인증:** `Authorization: Basic base64(clientId:clientSecret)` 필수.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "redirectUri 추가 성공"),
		@ApiResponse(
				responseCode = "401",
				description =
						"`INVALID_CLIENT_CREDENTIALS` — Authorization 헤더 누락, 디코딩 실패, clientId 미존재, BCrypt 불일치",
				content = @Content),
		@ApiResponse(
				responseCode = "403",
				description = "`FORBIDDEN_CLIENT_MISMATCH` — path clientId ≠ Basic Auth clientId",
				content = @Content)
	})
	@PostMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> addRedirectUri(
			@RequestHeader(value = "Authorization", required = false) String authHeader,
			@PathVariable String clientId,
			@RequestBody RedirectUriRequest request) {
		AuthResult auth = verifyBasicAuth(authHeader);
		if (auth.isFailure()) return auth.errorResponse();

		if (!clientId.equals(auth.clientId())) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(
							new ErrorResponse(
									"FORBIDDEN_CLIENT_MISMATCH", "경로의 clientId와 인증 clientId가 일치하지 않습니다."));
		}

		var updated = redirectUriService.addRedirectUri(clientId, request.uri());
		return ResponseEntity.ok(new RedirectUrisResponse(clientId, updated));
	}

	@Operation(
			summary = "redirectUri 제거",
			description =
					"특정 redirectUri를 삭제한다."
							+ " **인증:** `Authorization: Basic base64(clientId:clientSecret)` 필수.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "redirectUri 제거 성공"),
		@ApiResponse(
				responseCode = "401",
				description =
						"`INVALID_CLIENT_CREDENTIALS` — Authorization 헤더 누락, 디코딩 실패, clientId 미존재, BCrypt 불일치",
				content = @Content),
		@ApiResponse(
				responseCode = "403",
				description = "`FORBIDDEN_CLIENT_MISMATCH` — path clientId ≠ Basic Auth clientId",
				content = @Content)
	})
	@DeleteMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> removeRedirectUri(
			@RequestHeader(value = "Authorization", required = false) String authHeader,
			@PathVariable String clientId,
			@RequestBody RedirectUriRequest request) {
		AuthResult auth = verifyBasicAuth(authHeader);
		if (auth.isFailure()) return auth.errorResponse();

		if (!clientId.equals(auth.clientId())) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(
							new ErrorResponse(
									"FORBIDDEN_CLIENT_MISMATCH", "경로의 clientId와 인증 clientId가 일치하지 않습니다."));
		}

		var updated = redirectUriService.removeRedirectUri(clientId, request.uri());
		return ResponseEntity.ok(new RedirectUrisResponse(clientId, updated));
	}

	@Operation(
			summary = "redirectUri 전체 교체",
			description = "**인증:** `Authorization: Basic base64(clientId:clientSecret)` 필수.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "redirectUri 전체 교체 성공"),
		@ApiResponse(
				responseCode = "401",
				description =
						"`INVALID_CLIENT_CREDENTIALS` — Authorization 헤더 누락, 디코딩 실패, clientId 미존재, BCrypt 불일치",
				content = @Content),
		@ApiResponse(
				responseCode = "403",
				description = "`FORBIDDEN_CLIENT_MISMATCH` — path clientId ≠ Basic Auth clientId",
				content = @Content)
	})
	@PutMapping("/clients/{clientId}/redirect-uris")
	public ResponseEntity<?> replaceRedirectUris(
			@RequestHeader(value = "Authorization", required = false) String authHeader,
			@PathVariable String clientId,
			@RequestBody RedirectUrisReplaceRequest request) {
		AuthResult auth = verifyBasicAuth(authHeader);
		if (auth.isFailure()) return auth.errorResponse();

		if (!clientId.equals(auth.clientId())) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(
							new ErrorResponse(
									"FORBIDDEN_CLIENT_MISMATCH", "경로의 clientId와 인증 clientId가 일치하지 않습니다."));
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

	// ── private 헬퍼 ────────────────────────────────────────────

	/** Basic Auth 검증 결과 — 성공이면 clientId 값 보유, 실패면 errorResponse 보유 */
	private record AuthResult(String clientId, ResponseEntity<?> errorResponse) {
		static AuthResult success(String authenticatedClientId) {
			return new AuthResult(authenticatedClientId, null);
		}

		static AuthResult failure(ResponseEntity<?> response) {
			return new AuthResult(null, response);
		}

		boolean isFailure() {
			return errorResponse != null;
		}
	}

	/** Authorization 헤더에서 clientId + rawSecret 파싱. 실패 시 empty */
	private Optional<String[]> parseBasicAuth(String authorizationHeader) {
		if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
			return Optional.empty();
		}
		try {
			String encoded = authorizationHeader.substring(6);
			String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
			int colonIndex = decoded.indexOf(':');
			if (colonIndex < 0) {
				return Optional.empty();
			}
			String clientId = decoded.substring(0, colonIndex);
			String rawSecret = decoded.substring(colonIndex + 1);
			return Optional.of(new String[] {clientId, rawSecret});
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** Basic Auth 자격증명 검증. 성공 시 인증된 clientId 반환, 실패 시 errorResponse 반환 */
	private AuthResult verifyBasicAuth(String authorizationHeader) {
		ResponseEntity<?> invalidResponse =
				ResponseEntity.status(HttpStatus.UNAUTHORIZED)
						.header("WWW-Authenticate", "Basic realm=\"admin\"")
						.body(new ErrorResponse("INVALID_CLIENT_CREDENTIALS", "인증 정보가 없거나 형식이 올바르지 않습니다."));

		Optional<String[]> parsed = parseBasicAuth(authorizationHeader);
		if (parsed.isEmpty()) {
			return AuthResult.failure(invalidResponse);
		}

		String[] parts = parsed.get();
		String clientId = parts[0];
		String rawSecret = parts[1];

		RegisteredClient registeredClient = registeredClientRepository.findByClientId(clientId);
		if (registeredClient == null) {
			return AuthResult.failure(invalidResponse);
		}

		String storedSecret = registeredClient.getClientSecret();
		if (storedSecret == null) {
			// clientSecret 없는 클라이언트(authorization_code 타입)는 인증 불가
			return AuthResult.failure(invalidResponse);
		}
		String hash = storedSecret.startsWith("{bcrypt}") ? storedSecret.substring(8) : storedSecret;

		if (!passwordEncoder.matches(rawSecret, hash)) {
			return AuthResult.failure(invalidResponse);
		}

		return AuthResult.success(clientId);
	}
}
