package com.econo.auth.api.presentation.docs;

import com.econo.auth.api.presentation.controller.AdminClientController.RedirectUriRequest;
import com.econo.auth.api.presentation.controller.AdminClientController.RedirectUrisReplaceRequest;
import com.econo.auth.api.presentation.controller.AdminClientController.RegisterClientRequest;
import com.econo.common.auth.core.passport.Passport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

/** AdminClientController 의 Swagger 문서 정의. 어노테이션만 보유하며 메서드 규격은 컨트롤러와 동일하다. */
@Tag(name = "Admin")
public interface AdminClientApiDocs {

	@Operation(
			summary = "OAuth 클라이언트 등록",
			description =
					"새 OAuth 클라이언트(프론트엔드/모바일)를 등록하고 clientId를 발급한다.\n\n"
							+ "항상 authorization_code (PKCE) 클라이언트로 등록된다. `redirectUris` 필수.",
			security = @SecurityRequirement(name = "cookieAuth"))
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
	ResponseEntity<?> registerClient(Passport passport, RegisterClientRequest request);

	@Operation(
			summary = "클라이언트 조회 (redirectUri 포함)",
			security = @SecurityRequirement(name = "cookieAuth"))
	ResponseEntity<?> getClient(Passport passport, String clientId);

	@Operation(
			summary = "redirectUri 추가",
			description = "기존 redirectUri 유지하면서 새 URI를 추가한다.",
			security = @SecurityRequirement(name = "cookieAuth"))
	ResponseEntity<?> addRedirectUri(Passport passport, String clientId, RedirectUriRequest request);

	@Operation(summary = "redirectUri 제거", security = @SecurityRequirement(name = "cookieAuth"))
	ResponseEntity<?> removeRedirectUri(
			Passport passport, String clientId, RedirectUriRequest request);

	@Operation(summary = "redirectUri 전체 교체", security = @SecurityRequirement(name = "cookieAuth"))
	ResponseEntity<?> replaceRedirectUris(
			Passport passport, String clientId, RedirectUrisReplaceRequest request);
}
