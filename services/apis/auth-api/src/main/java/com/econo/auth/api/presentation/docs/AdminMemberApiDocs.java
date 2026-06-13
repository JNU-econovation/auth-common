package com.econo.auth.api.presentation.docs;

import com.econo.auth.api.presentation.dto.ErrorResponse;
import com.econo.auth.api.presentation.dto.PagedMembersResponse;
import com.econo.auth.api.presentation.dto.RoleUpdateRequest;
import com.econo.common.auth.core.passport.Passport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

/** AdminMemberController 의 Swagger 문서 정의. 어노테이션만 보유하며 메서드 규격은 컨트롤러와 동일하다. */
@Tag(name = "Admin")
public interface AdminMemberApiDocs {

	@Operation(
			summary = "회원 목록 조회",
			description = "ADMIN 또는 SUPER_ADMIN 역할 필요. role 파라미터로 필터링 가능.",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "조회 성공",
				content = @Content(schema = @Schema(implementation = PagedMembersResponse.class))),
		@ApiResponse(responseCode = "403", description = "ADMIN 역할 없음", content = @Content)
	})
	ResponseEntity<?> listMembers(Passport passport, int page, int size, String role);

	@Operation(
			summary = "회원 역할 변경",
			description =
					"SUPER_ADMIN 전용. 부여/회수 모두 이 엔드포인트 사용.\n\n"
							+ "**정책:**\n"
							+ "- 본인 역할 변경 불가 (`FORBIDDEN_SELF_ROLE_CHANGE`)\n"
							+ "- 마지막 SUPER_ADMIN 해제 불가 (`LAST_SUPER_ADMIN_CANNOT_BE_DEMOTED`)\n"
							+ "- 유효 역할: `USER`, `ADMIN`, `SUPER_ADMIN`",
			security = @SecurityRequirement(name = "cookieAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "역할 변경 성공"),
		@ApiResponse(
				responseCode = "400",
				description = "유효하지 않은 역할",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(
				responseCode = "403",
				description = "SUPER_ADMIN 역할 없음 또는 본인 변경 시도",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(
				responseCode = "404",
				description = "존재하지 않는 회원",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(
				responseCode = "409",
				description = "마지막 SUPER_ADMIN 해제 시도",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	ResponseEntity<?> updateRole(Passport passport, Long memberId, RoleUpdateRequest request);
}
