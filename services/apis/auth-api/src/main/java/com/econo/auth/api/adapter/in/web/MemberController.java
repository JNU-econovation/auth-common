package com.econo.auth.api.adapter.in.web;

import com.econo.auth.member.application.port.in.SignupUseCase;
import com.econo.auth.member.application.port.in.SignupUseCase.SignupCommand;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원 가입/로그아웃 REST 컨트롤러 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class MemberController {

	private final SignupUseCase signupUseCase;

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
	 * 로그아웃 — 서버 세션 무효화 및 SESSION 쿠키 만료
	 *
	 * <p>세션이 없는 경우에도 200 OK를 반환한다 (멱등 처리).
	 *
	 * @param session HttpSession (세션이 없으면 null)
	 * @param response HTTP 응답
	 * @return 200 OK
	 */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpSession session, HttpServletResponse response) {
		if (session != null) {
			try {
				session.invalidate();
			} catch (IllegalStateException e) {
				log.debug("Session already invalidated");
			}
		}
		ResponseCookie expiredCookie =
				ResponseCookie.from("SESSION", "")
						.httpOnly(true)
						.secure(true)
						.sameSite("None")
						.path("/")
						.maxAge(0)
						.build();
		response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie.toString());
		return ResponseEntity.ok().build();
	}
}
