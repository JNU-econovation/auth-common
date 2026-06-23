package com.econo.auth.api.presentation.docs;

import com.econo.auth.api.presentation.dto.MyClientItemResponse;
import com.econo.auth.api.presentation.dto.MyClientListResponse;
import com.econo.auth.api.presentation.dto.SelfRegisterClientRequest;
import com.econo.auth.api.presentation.dto.SelfRegisterClientResponse;
import com.econo.auth.api.presentation.dto.UpdateMyClientRequest;
import com.econo.common.auth.core.passport.Passport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;

/** ClientController 의 Swagger 문서 정의. 어노테이션만 보유하며 메서드 규격은 컨트롤러와 동일하다. */
@Tag(name = "Client")
public interface ClientApiDocs {

	@Operation(
			summary = "OAuth 클라이언트 셀프 등록",
			description =
					"인증된 회원이 자신의 서비스 앱을 SSO 클라이언트로 등록한다.\n\n"
							+ "항상 authorization_code (PKCE) 클라이언트로 등록된다. `redirectUris` 필수. 회원당 최대 5개 제한.\n\n"
							+ "`pathPrefix`와 `upstreamUrl`을 함께 제공하면 동일 트랜잭션에서 Gateway 동적 라우트를 1건 생성하고 즉시 반영한다."
							+ " 두 필드 중 하나만 제공하면 400 VALIDATION_FAILED.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(
				responseCode = "201",
				description = "클라이언트 등록 성공 — clientId + clientSecret 1회 반환 (라우트 생성 시 라우트 필드 포함)",
				content = @Content(schema = @Schema(implementation = SelfRegisterClientResponse.class))),
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
								+ "| VALIDATION_FAILED | clientName이 빈 문자열 또는 pathPrefix/upstreamUrl 중 하나만 있음 |\n"
								+ "| ROUTE_NAMESPACE_INVALID | pathPrefix가 /api/{namespace} 형태가 아님 |\n"
								+ "| ROUTE_UPSTREAM_INVALID | upstreamUrl SSRF 검증 실패 |",
				content = @Content),
		@ApiResponse(
				responseCode = "403",
				description =
						"| 코드 | 설명 |\n"
								+ "|------|------|\n"
								+ "| ROUTE_NAMESPACE_TAKEN | 네임스페이스를 다른 회원이 선점 |\n"
								+ "| ROUTE_PROTECTED | pathPrefix가 보호 경로와 충돌 |",
				content = @Content),
		@ApiResponse(
				responseCode = "409",
				description =
						"| 코드 | 설명 |\n"
								+ "|------|------|\n"
								+ "| DUPLICATE_CLIENT_NAME | clientName 중복 |\n"
								+ "| ROUTE_PATH_CONFLICT | pathPrefix 중복 |",
				content = @Content),
		@ApiResponse(
				responseCode = "422",
				description = "CLIENT_LIMIT_EXCEEDED — 회원당 최대 5개 초과",
				content = @Content)
	})
	ResponseEntity<?> registerClient(Passport passport, SelfRegisterClientRequest request);

	@Operation(
			summary = "내 클라이언트 목록 조회",
			description =
					"인증된 회원이 자신이 소유한 OAuth 클라이언트 전체 목록을 조회한다."
							+ " 각 항목에 연결된 Gateway 라우트 정보를 포함한다. clientSecret은 절대 반환하지 않는다.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "조회 성공 — 클라이언트 목록 (빈 목록이면 clients=[])",
				content = @Content(schema = @Schema(implementation = MyClientListResponse.class))),
		@ApiResponse(
				responseCode = "401",
				description = "X-User-Passport 헤더 누락 또는 파싱 실패",
				content = @Content)
	})
	ResponseEntity<MyClientListResponse> listMyClients(Passport passport);

	@Operation(
			summary = "내 클라이언트 단건 상세 조회",
			description =
					"인증된 회원이 자신이 소유한 OAuth 클라이언트 단건을 조회한다." + " 타인 소유 또는 미존재 clientId는 404로 존재 은닉 처리한다.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "조회 성공",
				content = @Content(schema = @Schema(implementation = MyClientItemResponse.class))),
		@ApiResponse(
				responseCode = "401",
				description = "X-User-Passport 헤더 누락 또는 파싱 실패",
				content = @Content),
		@ApiResponse(
				responseCode = "404",
				description = "CLIENT_NOT_FOUND — clientId 미존재 또는 타인 소유 (존재 은닉)",
				content = @Content)
	})
	ResponseEntity<MyClientItemResponse> getMyClient(Passport passport, String clientId);

	@Operation(
			summary = "내 클라이언트 수정 (전체 표현 교체)",
			description =
					"인증된 회원이 자신이 소유한 OAuth 클라이언트를 수정한다."
							+ " 백엔드가 현재 상태와 diff하여 변경분만 반영한다. clientSecret 재발급 없음.\n\n"
							+ "라우트 diff 동작: pathPrefix+upstreamUrl 있음→upsert, 없음→기존 라우트 삭제.\n\n"
							+ "네임스페이스 변경 시도 시 400 ROUTE_NAMESPACE_CHANGE_DENIED 반환.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "수정 성공 — 수정 후 상태 반환",
				content = @Content(schema = @Schema(implementation = MyClientItemResponse.class))),
		@ApiResponse(
				responseCode = "400",
				description =
						"| 코드 | 설명 |\n"
								+ "|------|------|\n"
								+ "| VALIDATION_FAILED | clientName 빈값 또는 pathPrefix/upstreamUrl 중 하나만 제공 |\n"
								+ "| REDIRECT_URI_REQUIRED | redirectUris 없음 |\n"
								+ "| ROUTE_NAMESPACE_INVALID | pathPrefix 형태 불일치 |\n"
								+ "| ROUTE_UPSTREAM_INVALID | upstreamUrl SSRF 검증 실패 |\n"
								+ "| ROUTE_NAMESPACE_CHANGE_DENIED | 네임스페이스 변경 시도 |",
				content = @Content),
		@ApiResponse(
				responseCode = "401",
				description = "X-User-Passport 헤더 누락 또는 파싱 실패",
				content = @Content),
		@ApiResponse(
				responseCode = "403",
				description =
						"| 코드 | 설명 |\n"
								+ "|------|------|\n"
								+ "| ROUTE_NAMESPACE_TAKEN | 네임스페이스를 다른 회원이 선점 |\n"
								+ "| ROUTE_PROTECTED | pathPrefix가 보호 경로와 충돌 |",
				content = @Content),
		@ApiResponse(
				responseCode = "404",
				description = "CLIENT_NOT_FOUND — clientId 미존재 또는 타인 소유 (존재 은닉)",
				content = @Content),
		@ApiResponse(
				responseCode = "409",
				description =
						"| 코드 | 설명 |\n"
								+ "|------|------|\n"
								+ "| DUPLICATE_CLIENT_NAME | clientName 중복 |\n"
								+ "| ROUTE_PATH_CONFLICT | pathPrefix 중복 |",
				content = @Content)
	})
	ResponseEntity<MyClientItemResponse> updateMyClient(
			Passport passport, String clientId, @Valid UpdateMyClientRequest request);

	@Operation(
			summary = "내 클라이언트 하드 삭제",
			description =
					"인증된 회원이 자신이 소유한 OAuth 클라이언트를 하드 삭제한다."
							+ " service_client, SAS oauth2_registered_client, 연결 service_route가 단일 트랜잭션에서 캐스케이드 삭제된다."
							+ " 타인 소유 또는 미존재 clientId는 404로 존재 은닉 처리한다.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "삭제 성공", content = @Content),
		@ApiResponse(
				responseCode = "401",
				description = "X-User-Passport 헤더 누락 또는 파싱 실패",
				content = @Content),
		@ApiResponse(
				responseCode = "404",
				description = "CLIENT_NOT_FOUND — clientId 미존재 또는 타인 소유 (존재 은닉)",
				content = @Content)
	})
	ResponseEntity<Void> deleteMyClient(Passport passport, String clientId);
}
