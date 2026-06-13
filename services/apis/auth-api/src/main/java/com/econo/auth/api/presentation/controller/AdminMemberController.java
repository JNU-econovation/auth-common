package com.econo.auth.api.presentation.controller;

import com.econo.auth.member.application.domain.Member;
import com.econo.auth.member.application.usecase.MemberQueryUseCase;
import com.econo.common.auth.core.passport.Passport;
import com.econo.common.auth.core.passport.Roles;
import com.econo.common.auth.web.annotation.PassportAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 목록 조회 및 역할 관리 컨트롤러 (Admin)
 *
 * <ul>
 *   <li>목록 조회: ADMIN 또는 SUPER_ADMIN
 *   <li>역할 변경: SUPER_ADMIN 전용
 * </ul>
 *
 * <p>정책:
 *
 * <ul>
 *   <li>본인 역할 변경 불가
 *   <li>마지막 SUPER_ADMIN 해제 불가
 *   <li>유효 역할: USER, ADMIN, SUPER_ADMIN
 * </ul>
 */
@Slf4j
@Tag(name = "Admin — Member Management", description = "회원 목록 조회 및 역할 관리 API")
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

	private static final Set<String> VALID_ROLES = Set.of("USER", "ADMIN", "SUPER_ADMIN");

	private final MemberQueryUseCase memberQueryUseCase;

	// ── Response DTO ─────────────────────────────────────────

	public record MemberSummary(
			Long memberId, String name, String loginId, Integer generation, String status, String role) {

		static MemberSummary from(Member m) {
			return new MemberSummary(
					m.getId(),
					m.getName(),
					m.getLoginId(),
					m.getGeneration(),
					m.getStatus().name(),
					m.getRole());
		}
	}

	public record PagedMembersResponse(
			List<MemberSummary> content, long totalElements, int totalPages, int page, int size) {}

	public record RoleUpdateRequest(@NotBlank String role) {}

	public record ErrorResponse(String errorCode, String message, LocalDateTime timestamp) {
		public ErrorResponse(String errorCode, String message) {
			this(errorCode, message, LocalDateTime.now());
		}
	}

	// ── Endpoints ────────────────────────────────────────────

	@Operation(
			summary = "회원 목록 조회",
			description = "ADMIN 또는 SUPER_ADMIN 역할 필요. role 파라미터로 필터링 가능.",
			security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공"),
		@ApiResponse(responseCode = "403", description = "ADMIN 역할 없음", content = @Content)
	})
	@GetMapping
	public ResponseEntity<?> listMembers(
			@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN}) Passport passport,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String role) {

		List<MemberSummary> content =
				memberQueryUseCase.findPaged(page, size, role).stream().map(MemberSummary::from).toList();
		long total = memberQueryUseCase.count(role);
		int totalPages = (int) Math.ceil((double) total / size);

		return ResponseEntity.ok(new PagedMembersResponse(content, total, totalPages, page, size));
	}

	@Operation(
			summary = "회원 역할 변경",
			description =
					"SUPER_ADMIN 전용. 부여/회수 모두 이 엔드포인트 사용.\n\n"
							+ "**정책:**\n"
							+ "- 본인 역할 변경 불가 (`FORBIDDEN_SELF_ROLE_CHANGE`)\n"
							+ "- 마지막 SUPER_ADMIN 해제 불가 (`LAST_SUPER_ADMIN_CANNOT_BE_DEMOTED`)\n"
							+ "- 유효 역할: `USER`, `ADMIN`, `SUPER_ADMIN`",
			security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "역할 변경 성공"),
		@ApiResponse(responseCode = "400", description = "유효하지 않은 역할", content = @Content),
		@ApiResponse(
				responseCode = "403",
				description = "SUPER_ADMIN 역할 없음 또는 본인 변경 시도",
				content = @Content),
		@ApiResponse(responseCode = "404", description = "존재하지 않는 회원", content = @Content),
		@ApiResponse(responseCode = "409", description = "마지막 SUPER_ADMIN 해제 시도", content = @Content)
	})
	@PatchMapping("/{memberId}/role")
	public ResponseEntity<?> updateRole(
			@PassportAuth(requiredRoles = {Roles.SUPER_ADMIN}) Passport passport,
			@PathVariable Long memberId,
			@RequestBody RoleUpdateRequest request) {

		// 본인 역할 변경 불가
		if (passport.getMemberId() != null && passport.getMemberId().equals(memberId)) {
			return ResponseEntity.status(403)
					.body(new ErrorResponse("FORBIDDEN_SELF_ROLE_CHANGE", "본인의 역할은 변경할 수 없습니다."));
		}

		// 유효 역할 검증
		String newRole = request.role().toUpperCase();
		if (!VALID_ROLES.contains(newRole)) {
			return ResponseEntity.status(400)
					.body(
							new ErrorResponse(
									"INVALID_ROLE", "유효하지 않은 역할입니다. 허용: " + String.join(", ", VALID_ROLES)));
		}

		// 대상 회원 존재 여부
		Member target = memberQueryUseCase.findById(memberId).orElse(null);
		if (target == null) {
			return ResponseEntity.status(404).body(new ErrorResponse("NOT_FOUND", "존재하지 않는 회원입니다."));
		}

		// 마지막 SUPER_ADMIN 해제 방지
		if ("SUPER_ADMIN".equals(target.getRole()) && !"SUPER_ADMIN".equals(newRole)) {
			long superAdminCount = memberQueryUseCase.countByRole("SUPER_ADMIN");
			if (superAdminCount <= 1) {
				return ResponseEntity.status(409)
						.body(
								new ErrorResponse(
										"LAST_SUPER_ADMIN_CANNOT_BE_DEMOTED", "마지막 SUPER_ADMIN은 해제할 수 없습니다."));
			}
		}

		memberQueryUseCase.updateRole(memberId, newRole);
		return ResponseEntity.ok(new MemberRoleResponse(memberId, newRole));
	}

	record MemberRoleResponse(Long memberId, String role) {}
}
