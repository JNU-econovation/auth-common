package com.econo.auth.api.presentation.docs;

import com.econo.auth.api.presentation.dto.SignupRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

/** MemberController 의 Swagger 문서 정의. 어노테이션만 보유하며 메서드 규격은 컨트롤러와 동일하다. */
@Tag(name = "Auth")
public interface MemberApiDocs {

	@Operation(summary = "회원 가입", description = "loginId/password 기반 회원 가입. 성공 시 201 반환.")
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "가입 성공"),
		@ApiResponse(
				responseCode = "400",
				description = "VALIDATION_FAILED 또는 INVALID_PASSWORD_POLICY",
				content = @Content),
		@ApiResponse(
				responseCode = "409",
				description = "MEMBER_ALREADY_EXISTS — loginId 중복",
				content = @Content)
	})
	ResponseEntity<Void> signup(SignupRequest request);
}
