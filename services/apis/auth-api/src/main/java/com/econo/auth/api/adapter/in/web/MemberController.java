package com.econo.auth.api.adapter.in.web;

import com.econo.auth.core.member.application.port.in.SignupUseCase;
import com.econo.auth.core.member.application.port.in.SignupUseCase.SignupCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth — Signup", description = "회원 가입 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class MemberController {

	private final SignupUseCase signupUseCase;

	@Operation(summary = "회원 가입", description = "loginId/password 기반 회원 가입. 성공 시 201 반환.")
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "가입 성공"),
		@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 또는 INVALID_PASSWORD_POLICY", content = @Content),
		@ApiResponse(responseCode = "409", description = "MEMBER_ALREADY_EXISTS — loginId 중복", content = @Content)
	})
	@PostMapping("/signup")
	public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest request) {
		signupUseCase.signup(
				new SignupCommand(
						request.name(),
						request.loginId(),
						request.password(),
						request.generation(),
						request.status()));
		return ResponseEntity.status(HttpStatus.CREATED).build();
	}
}
