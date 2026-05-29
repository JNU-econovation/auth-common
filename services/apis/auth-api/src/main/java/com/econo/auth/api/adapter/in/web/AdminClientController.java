package com.econo.auth.api.adapter.in.web;

import com.econo.auth.api.application.usecase.RegisterOAuthClientService;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand;
import com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult;
import com.econo.auth.api.domain.GrantType;
import com.econo.auth.api.domain.ServiceRoute;
import com.econo.auth.api.exception.UnsupportedGrantTypeException;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.beans.ConstructorProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 클라이언트 관리 REST 컨트롤러 (Admin)
 *
 * <p>POST /api/v1/admin/clients — OAuth 클라이언트 등록 GET /api/v1/admin/routes — Gateway용 라우트 목록
 * (Internal API Key 인증)
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

	@Value("${AUTH_INTERNAL_API_KEY}")
	private String internalApiKey;

	/**
	 * OAuth 클라이언트 등록 요청 DTO
	 *
	 * @param grantType 그랜트 타입 (authorization_code, client_credentials)
	 * @param clientName 클라이언트 이름
	 * @param redirectUris 리다이렉트 URI 목록 (authorization_code 전용)
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
		public RegisterClientRequest {}
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

	/**
	 * 라우트 목록 응답 DTO
	 *
	 * @param routes 라우트 목록
	 */
	public record RoutesResponse(List<RouteInfo> routes) {}

	/**
	 * 라우트 정보 DTO
	 *
	 * @param routeId 라우트 ID
	 * @param clientId 클라이언트 ID
	 * @param upstreamUrl 업스트림 URL
	 * @param pathPrefix 경로 접두사
	 */
	public record RouteInfo(String routeId, String clientId, String upstreamUrl, String pathPrefix) {}

	/** 에러 응답 DTO */
	public record ErrorResponse(String errorCode, String message, LocalDateTime timestamp) {
		public ErrorResponse(String errorCode, String message) {
			this(errorCode, message, LocalDateTime.now());
		}
	}

	/**
	 * OAuth 클라이언트 등록
	 *
	 * @param request 등록 요청
	 * @return 201 Created + 등록 결과
	 */
	@Operation(
			summary = "OAuth 클라이언트 등록",
			description =
					"새 OAuth 클라이언트를 등록하고 clientId를 발급한다.\n\n"
							+ "**grantType별 동작:**\n"
							+ "- `authorization_code`: PKCE 필수 공개 클라이언트. `redirectUris` 필수. clientSecret 미발급.\n"
							+ "- `client_credentials`: 비밀 클라이언트. clientSecret **1회만** 응답에 포함(이후 재조회 불가). "
							+ "`upstreamUrl` + `pathPrefix` 지정 시 Gateway 동적 라우트 자동 등록.\n\n"
							+ "**인증:** `X-Internal-Api-Key` 헤더 필수 (서버 내부망 전용)")
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "클라이언트 등록 성공"),
		@ApiResponse(
				responseCode = "400",
				description =
						"| 코드 | 설명 |\n"
								+ "|------|------|\n"
								+ "| REDIRECT_URI_REQUIRED | authorization_code인데 redirectUris 없음 |\n"
								+ "| UNSUPPORTED_GRANT_TYPE | 지원하지 않는 grantType |\n"
								+ "| VALIDATION_FAILED | clientName이 빈 문자열 |",
				content = @Content),
		@ApiResponse(responseCode = "401", description = "X-Internal-Api-Key 없거나 틀림", content = @Content),
		@ApiResponse(responseCode = "409", description = "DUPLICATE_RESOURCE — clientName 또는 pathPrefix 중복", content = @Content)
	})
	@PostMapping("/clients")
	public ResponseEntity<?> registerClient(
			@Parameter(description = "내부 API 키 (환경변수 AUTH_INTERNAL_API_KEY)", required = true)
					@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKeyHeader,
			@Valid @RequestBody RegisterClientRequest request) {
		if (!isValidApiKey(apiKeyHeader)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(new ErrorResponse("UNAUTHORIZED", "유효하지 않은 Internal API Key입니다."));
		}
		// grantType 검증
		GrantType grantType = parseGrantType(request.grantType());

		// authorization_code 타입에서 redirectUris 검증
		if (grantType == GrantType.AUTHORIZATION_CODE
				&& (request.redirectUris() == null || request.redirectUris().isEmpty())) {
			return ResponseEntity.badRequest()
					.body(
							new ErrorResponse(
									"REDIRECT_URI_REQUIRED", "authorization_code 타입은 redirectUris가 필수입니다."));
		}

		Set<String> redirectUris =
				request.redirectUris() != null ? request.redirectUris() : new HashSet<>();

		RegisterOAuthClientCommand command =
				new RegisterOAuthClientCommand(
						grantType,
						request.clientName(),
						redirectUris.isEmpty() ? null : redirectUris,
						request.upstreamUrl(),
						request.pathPrefix());

		RegisterOAuthClientResult result = registerOAuthClientService.register(command);

		RegisterClientResponse response =
				new RegisterClientResponse(result.clientId(), result.clientSecret(), result.routeId());

		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	/**
	 * Gateway용 라우트 목록 조회 (Internal API Key 인증)
	 *
	 * @param internalApiKeyHeader X-Internal-Api-Key 헤더 값
	 * @return 200 OK + 라우트 목록
	 */
	@Operation(
			summary = "Gateway 라우트 목록 조회",
			description =
					"등록된 service_route를 전체 반환한다. **api-gateway가 30초마다 폴링**하는 내부 전용 엔드포인트.\n\n"
							+ "각 라우트는 `pathPrefix → upstreamUrl` 형태로 Gateway가 요청을 포워딩하는 데 사용된다.\n\n"
							+ "**인증:** `X-Internal-Api-Key` 헤더 필수")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "라우트 목록 반환"),
		@ApiResponse(responseCode = "401", description = "X-Internal-Api-Key 없거나 틀림", content = @Content)
	})
	@GetMapping("/routes")
	public ResponseEntity<?> getRoutes(
			@Parameter(description = "내부 API 키", required = true)
					@RequestHeader(value = "X-Internal-Api-Key", required = false) String internalApiKeyHeader) {
		// Internal API Key 검증
		if (internalApiKeyHeader == null
				|| !MessageDigest.isEqual(
						internalApiKey.getBytes(StandardCharsets.UTF_8),
						internalApiKeyHeader.getBytes(StandardCharsets.UTF_8))) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(new ErrorResponse("UNAUTHORIZED", "유효하지 않은 Internal API Key입니다."));
		}

		List<ServiceRoute> routes = registerOAuthClientService.getRoutes();

		List<RouteInfo> routeInfos =
				routes.stream()
						.map(r -> new RouteInfo(r.routeId(), r.clientId(), r.upstreamUrl(), r.pathPrefix()))
						.toList();

		return ResponseEntity.ok(new RoutesResponse(routeInfos));
	}

	private boolean isValidApiKey(String header) {
		if (header == null) return false;
		return MessageDigest.isEqual(
				internalApiKey.getBytes(StandardCharsets.UTF_8),
				header.getBytes(StandardCharsets.UTF_8));
	}

	private GrantType parseGrantType(String grantTypeStr) {
		if (grantTypeStr == null) {
			return null;
		}
		return switch (grantTypeStr.toLowerCase()) {
			case "authorization_code" -> GrantType.AUTHORIZATION_CODE;
			case "client_credentials" -> GrantType.CLIENT_CREDENTIALS;
			default -> throw new UnsupportedGrantTypeException(grantTypeStr);
		};
	}
}
