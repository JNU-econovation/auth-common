package com.econo.auth.api.adapter.in.web;

import com.econo.auth.core.member.application.port.in.LoginUseCase;
import com.econo.auth.core.member.application.port.in.LoginUseCase.LoginCommand;
import com.econo.auth.core.member.application.port.in.LoginUseCase.LoginResult;
import com.econo.auth.core.member.application.port.in.SignupUseCase;
import com.econo.auth.core.member.application.port.in.SignupUseCase.SignupCommand;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원 가입/로그인/로그아웃 REST 컨트롤러 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class MemberController {

	private final SignupUseCase signupUseCase;
	private final LoginUseCase loginUseCase;
	private final long expirySeconds;
	private final String cookieName;

	public MemberController(
			SignupUseCase signupUseCase,
			LoginUseCase loginUseCase,
			@Value("${auth.jwt.expiry-seconds:3600}") long expirySeconds,
			@Value("${auth.jwt.cookie-name:auth_token}") String cookieName) {
		this.signupUseCase = signupUseCase;
		this.loginUseCase = loginUseCase;
		this.expirySeconds = expirySeconds;
		this.cookieName = cookieName;
	}

	/**
	 * 회원 가입
	 *
	 * @param request 가입 요청
	 * @return 201 Created
	 */
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

	/**
	 * 로그인
	 *
	 * @param request 로그인 요청
	 * @param response HTTP 응답
	 * @return 200 OK (Set-Cookie)
	 */
	@PostMapping("/login")
	public ResponseEntity<Void> login(
			@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
		LoginResult result =
				loginUseCase.login(new LoginCommand(request.loginId(), request.password()));
		ResponseCookie cookie = buildAuthCookie(result.jwtToken());
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		return ResponseEntity.ok().build();
	}

	/**
	 * 로그아웃 (쿠키 만료)
	 *
	 * @param response HTTP 응답
	 * @return 200 OK
	 */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletResponse response) {
		ResponseCookie cookie = expireAuthCookie();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		return ResponseEntity.ok().build();
	}

	/** auth_token 쿠키 생성 */
	private ResponseCookie buildAuthCookie(String jwt) {
		return ResponseCookie.from(cookieName, jwt)
				.httpOnly(true)
				.secure(true)
				.sameSite("Strict")
				.path("/")
				.maxAge(expirySeconds)
				.build();
	}

	/** auth_token 쿠키 만료 */
	private ResponseCookie expireAuthCookie() {
		return ResponseCookie.from(cookieName, "")
				.httpOnly(true)
				.secure(true)
				.sameSite("Strict")
				.path("/")
				.maxAge(0)
				.build();
	}
}
