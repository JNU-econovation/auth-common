package com.econo.auth.api.filter;

import com.econo.auth.api.adapter.in.web.TokenCookieManager;
import com.econo.auth.api.application.LoginRedirectResolver;
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
 * <p>{@code Client-Type: WEB} (기본): AT + RT → HttpOnly 쿠키, clientId로 redirect_uri 조회 후 302 리다이렉트
 * <br>
 * {@code Client-Type: APP}: AT + RT 모두 → body (200 OK)
 */
@Slf4j
public class JsonLoginAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

	private static final AntPathRequestMatcher LOGIN_MATCHER =
			new AntPathRequestMatcher("/api/v1/auth/login", "POST");
	private static final String CLIENT_TYPE_HEADER = "Client-Type";

	private final ObjectMapper objectMapper;
	private final LoginTokenService loginTokenService;
	private final TokenCookieManager cookieManager;
	private final LoginRedirectResolver loginRedirectResolver;
	private final String defaultRedirectUrl;

	public JsonLoginAuthenticationFilter(
			AuthenticationManager authenticationManager,
			ObjectMapper objectMapper,
			LoginTokenService loginTokenService,
			TokenCookieManager cookieManager,
			LoginRedirectResolver loginRedirectResolver,
			String defaultRedirectUrl) {
		super(LOGIN_MATCHER, authenticationManager);
		this.objectMapper = objectMapper;
		this.loginTokenService = loginTokenService;
		this.cookieManager = cookieManager;
		this.loginRedirectResolver = loginRedirectResolver;
		this.defaultRedirectUrl = defaultRedirectUrl;
		setSecurityContextRepository(new NullSecurityContextRepository());
	}

	@Override
	public Authentication attemptAuthentication(
			HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {
		try {
			LoginRequest loginRequest =
					objectMapper.readValue(request.getInputStream(), LoginRequest.class);
			String loginId = loginRequest.loginId() != null ? loginRequest.loginId() : "";
			String password = loginRequest.password() != null ? loginRequest.password() : "";
			request.setAttribute("clientId", loginRequest.clientId());
			return getAuthenticationManager()
					.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(loginId, password));
		} catch (IOException e) {
			throw new org.springframework.security.authentication.BadCredentialsException(
					"Invalid login request body", e);
		}
	}

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

		if (isApp) {
			// APP: AT + RT 모두 body (200 OK)
			var body =
					com.econo.auth.api.adapter.in.web.LoginResponse.app(
							tokens.accessToken(), tokens.accessExpiredAt(), tokens.refreshToken());
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			objectMapper.writeValue(response.getWriter(), body);
		} else {
			// WEB: AT + RT → HttpOnly 쿠키, clientId로 redirect_uri 조회 후 302 리다이렉트
			// 쿠키 헤더 추가는 sendRedirect(응답 커밋) 전에 반드시 수행
			cookieManager.setAtCookie(response, tokens.accessToken());
			cookieManager.setRtCookie(response, tokens.refreshToken());

			String clientId = (String) request.getAttribute("clientId");
			String target = loginRedirectResolver.resolve(clientId, defaultRedirectUrl);
			response.sendRedirect(target);
		}
	}

	@Override
	protected void unsuccessfulAuthentication(
			HttpServletRequest request, HttpServletResponse response, AuthenticationException failed)
			throws IOException {
		log.warn("Login failed: {}", failed.getMessage());
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		Map<String, Object> body = new HashMap<>();
		body.put("errorCode", "INVALID_CREDENTIALS");
		body.put("message", "아이디 또는 비밀번호가 올바르지 않습니다.");
		body.put("timestamp", LocalDateTime.now().toString());
		body.put("fieldErrors", null);
		objectMapper.writeValue(response.getWriter(), body);
	}

	private record LoginRequest(String loginId, String password, String clientId) {}
}
