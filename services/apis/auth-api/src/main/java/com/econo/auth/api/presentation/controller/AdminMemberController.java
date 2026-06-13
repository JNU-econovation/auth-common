package com.econo.auth.api.presentation.controller;

import com.econo.auth.api.presentation.docs.AdminMemberApiDocs;
import com.econo.auth.api.presentation.dto.ErrorResponse;
import com.econo.auth.api.presentation.dto.MemberSummary;
import com.econo.auth.api.presentation.dto.PagedMembersResponse;
import com.econo.auth.api.presentation.dto.RoleUpdateRequest;
import com.econo.auth.member.application.domain.Member;
import com.econo.auth.member.application.usecase.MemberQueryUseCase;
import com.econo.common.auth.core.passport.Passport;
import com.econo.common.auth.core.passport.Roles;
import com.econo.common.auth.web.annotation.PassportAuth;
import jakarta.validation.Valid;
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
@RestController
@RequestMapping("/api/v1/admin/members")
@RequiredArgsConstructor
public class AdminMemberController implements AdminMemberApiDocs {

	private static final Set<String> VALID_ROLES = Set.of("USER", "ADMIN", "SUPER_ADMIN");

	private final MemberQueryUseCase memberQueryUseCase;

	// ── Endpoints ────────────────────────────────────────────

	@Override
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

	@Override
	@PatchMapping("/{memberId}/role")
	public ResponseEntity<?> updateRole(
			@PassportAuth(requiredRoles = {Roles.SUPER_ADMIN}) Passport passport,
			@PathVariable Long memberId,
			@Valid @RequestBody RoleUpdateRequest request) {

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
