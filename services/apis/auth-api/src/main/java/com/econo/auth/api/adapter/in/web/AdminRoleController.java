package com.econo.auth.api.adapter.in.web;

import com.econo.auth.member.application.port.out.MemberRepository;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 역할(role) 관리 컨트롤러 — X-Internal-Api-Key 보호 (CLI 전용)
 *
 * <p>최초 관리자 계정 부여 등 Bootstrap 목적으로만 사용한다. 이후 역할 변경은 관리자 UI에서 수행한다.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/internal/members")
@RequiredArgsConstructor
public class AdminRoleController {

	private final MemberRepository memberRepository;

	@Value("${AUTH_INTERNAL_API_KEY}")
	private String internalApiKey;

	public record RoleUpdateRequest(@NotBlank String role) {}

	public record ErrorResponse(String errorCode, String message, LocalDateTime timestamp) {
		public ErrorResponse(String errorCode, String message) {
			this(errorCode, message, LocalDateTime.now());
		}
	}

	/** 특정 회원의 역할을 변경한다. 최초 관리자 부여 목적. */
	@PutMapping("/{memberId}/role")
	public ResponseEntity<?> updateRole(
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKeyHeader,
			@PathVariable Long memberId,
			@RequestBody RoleUpdateRequest request) {
		if (!isValidApiKey(apiKeyHeader)) {
			return ResponseEntity.status(401)
					.body(new ErrorResponse("UNAUTHORIZED", "유효하지 않은 Internal API Key입니다."));
		}

		boolean exists = memberRepository.findById(memberId).isPresent();
		if (!exists) {
			return ResponseEntity.status(404).body(new ErrorResponse("NOT_FOUND", "존재하지 않는 회원입니다."));
		}

		memberRepository.updateRole(memberId, request.role());
		return ResponseEntity.ok().build();
	}

	private boolean isValidApiKey(String header) {
		if (header == null) return false;
		return MessageDigest.isEqual(
				internalApiKey.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
	}
}
