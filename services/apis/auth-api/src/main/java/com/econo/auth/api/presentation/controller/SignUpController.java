package com.econo.auth.api.presentation.controller;

import com.econo.auth.api.presentation.docs.SignUpApiDocs;
import com.econo.auth.api.presentation.dto.SignupRequest;
import com.econo.auth.member.application.usecase.SignupUseCase;
import com.econo.auth.member.application.usecase.SignupUseCase.SignupCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class SignUpController implements SignUpApiDocs {

	private final SignupUseCase signupUseCase;

	@Override
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
