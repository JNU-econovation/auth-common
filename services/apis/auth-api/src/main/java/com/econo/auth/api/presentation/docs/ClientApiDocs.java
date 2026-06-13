package com.econo.auth.api.presentation.docs;

import com.econo.auth.api.presentation.dto.SelfRegisterClientRequest;
import com.econo.auth.api.presentation.dto.SelfRegisterClientResponse;
import com.econo.common.auth.core.passport.Passport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

/** ClientController 의 Swagger 문서 정의. 어노테이션만 보유하며 메서드 규격은 컨트롤러와 동일하다. */
@Tag(name = "Client")
public interface ClientApiDocs {

	@Operation(
			summary = "OAuth 클라이언트 셀프 등록",
			description =
					"인증된 회원이 자신의 서비스 앱을 SSO 클라이언트로 등록한다.\n\n"
							+ "항상 authorization_code (PKCE) 클라이언트로 등록된다. `redirectUris` 필수. 회원당 최대 5개 제한.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(
				responseCode = "201",
				description = "클라이언트 등록 성공 — clientId + clientSecret 1회 반환",
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
								+ "| VALIDATION_FAILED | clientName이 빈 문자열 |",
				content = @Content),
		@ApiResponse(responseCode = "409", description = "DUPLICATE_CLIENT_NAME", content = @Content),
		@ApiResponse(
				responseCode = "422",
				description = "CLIENT_LIMIT_EXCEEDED — 회원당 최대 5개 초과",
				content = @Content)
	})
	ResponseEntity<?> registerClient(Passport passport, SelfRegisterClientRequest request);
}
