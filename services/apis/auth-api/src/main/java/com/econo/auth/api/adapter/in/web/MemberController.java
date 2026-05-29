package com.econo.auth.api.adapter.in.web;

import com.econo.auth.core.member.application.port.in.SignupUseCase;
import com.econo.auth.core.member.application.port.in.SignupUseCase.SignupCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원 가입 REST 컨트롤러 — 로그아웃은 ReissueController로 이전 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class MemberController {

	private final SignupUseCase signupUseCase;

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
