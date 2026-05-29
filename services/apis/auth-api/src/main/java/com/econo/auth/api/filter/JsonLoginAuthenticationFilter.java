package com.econo.auth.api.filter;

import com.econo.auth.api.adapter.in.web.LoginResponse;
import com.econo.auth.api.adapter.in.web.TokenCookieManager;
import com.econo.auth.api.application.LoginTokenService;
import com.econo.auth.api.application.LoginTokenService.TokenPair;
import com.econo.auth.api.security.MemberUserDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * JSON 로그인 필터 — 인증 성공 시 AT/RT JWT를 발급한다 (세션 없음)
 *
 * <p>{@code Client-Type: WEB} (기본): AT → body, RT → HttpOnly 쿠키<br>
 * {@code Client-Type: APP}: AT + RT 모두 → body
 */
@Slf4j
public class JsonLoginAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

	private static final AntPathRequestMatcher LOGIN_MATCHER =
			new AntPathRequestMatcher("/api/v1/auth/login", "POST");
	private static final String CLIENT_TYPE_HEADER = "Client-Type";

	private final ObjectMapper objectMapper;
	private final LoginTokenService loginTokenService;
	private final TokenCookieManager cookieManager;

	public JsonLoginAuthenticationFilter(
			AuthenticationManager authenticationManager,
			ObjectMapper objectMapper,
			LoginTokenService loginTokenService,
			TokenCookieManager cookieManager) {
		super(LOGIN_MATCHER, authenticationManager);
		this.objectMapper = objectMapper;
		this.loginTokenService = loginTokenService;
		this.cookieManager = cookieManager;
		// 세션 불필요 — JWT로 stateless 인증
		setSecurityContextRepository(new NullSecurityContextRepository());
	}

	@Override
	public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
			throws AuthenticationException {
		try {
			LoginRequest loginRequest = objectMapper.readValue(request.getInputStream(), LoginRequest.class);
			String loginId = loginRequest.loginId() != null ? loginRequest.loginId() : "";
			String password = loginRequest.password() != null ? loginRequest.password() : "";
			return getAuthenticationManager()
					.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(loginId, password));
		} catch (IOException e) {
			throw new org.springframework.security.authentication.BadCredentialsException(
					"Invalid login request body", e);
		}
	}

	/** 인증 성공 — Client-Type에 따라 AT/RT 응답 */
	@Override
	protected void successfulAuthentication(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain chain,
			Authentication authResult)
			throws IOException {
		MemberUserDetails userDetails = (MemberUserDetails) authResult.getPrincipal();
		TokenPair tokens = loginTokenService.issue(userDetails.getMember());

		String clientType = request.getHeader(CLIENT_TYPE_HEADER);
		boolean isApp = "APP".equalsIgnoreCase(clientType);

		LoginResponse body;
		if (isApp) {
			body = LoginResponse.app(tokens.accessToken(), tokens.accessExpiredAt(), tokens.refreshToken());
		} else {
			cookieManager.setRtCookie(response, tokens.refreshToken());
			body = LoginResponse.web(tokens.accessToken(), tokens.accessExpiredAt());
		}

		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(), body);
	}

	@Override
	protected void unsuccessfulAuthentication(
			HttpServletRequest request, HttpServletResponse response, AuthenticationException failed)
			throws IOException {
		log.warn("Login failed: {}", failed.getMessage());
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");

		Map<String, Object> errorBody = new HashMap<>();
		errorBody.put("errorCode", "INVALID_CREDENTIALS");
		errorBody.put("message", "아이디 또는 비밀번호가 올바르지 않습니다.");
		errorBody.put("timestamp", LocalDateTime.now().toString());
		errorBody.put("fieldErrors", null);

		objectMapper.writeValue(response.getWriter(), errorBody);
	}

	private record LoginRequest(String loginId, String password) {}
}
