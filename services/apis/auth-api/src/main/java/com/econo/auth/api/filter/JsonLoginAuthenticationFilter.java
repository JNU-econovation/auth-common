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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * JSON 로그인 필터 — SAS Authorization Code + PKCE 흐름과 직접 JWT 흐름을 모두 지원
 *
 * <h2>흐름 자동 감지</h2>
 *
 * <ul>
 *   <li><b>SAS 흐름</b>: 세션에 {@code /oauth2/authorize} 저장 요청이 있음 → 세션에 인증 저장 → SAS가 코드 발급 →
 *       redirect_uri로 리다이렉트
 *   <li><b>직접 흐름</b>: 저장 요청 없음 → JWT AT/RT 직접 발급
 *       <ul>
 *         <li>{@code Client-Type: WEB} (기본): AT → body, RT → HttpOnly 쿠키
 *         <li>{@code Client-Type: APP}: AT + RT 모두 → body
 *       </ul>
 * </ul>
 */
@Slf4j
public class JsonLoginAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

	private static final AntPathRequestMatcher LOGIN_MATCHER =
			new AntPathRequestMatcher("/api/v1/auth/login", "POST");
	private static final String CLIENT_TYPE_HEADER = "Client-Type";

	private final ObjectMapper objectMapper;
	private final LoginTokenService loginTokenService;
	private final TokenCookieManager cookieManager;

	private final HttpSessionSecurityContextRepository sessionContextRepository =
			new HttpSessionSecurityContextRepository();
	private final RequestCache requestCache = new HttpSessionRequestCache();
	private final SavedRequestAwareAuthenticationSuccessHandler savedRequestHandler =
			new SavedRequestAwareAuthenticationSuccessHandler();

	public JsonLoginAuthenticationFilter(
			AuthenticationManager authenticationManager,
			ObjectMapper objectMapper,
			LoginTokenService loginTokenService,
			TokenCookieManager cookieManager) {
		super(LOGIN_MATCHER, authenticationManager);
		this.objectMapper = objectMapper;
		this.loginTokenService = loginTokenService;
		this.cookieManager = cookieManager;
	}

	@Override
	public Authentication attemptAuthentication(
			HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {
		try {
			LoginRequest loginRequest =
					objectMapper.readValue(request.getInputStream(), LoginRequest.class);
			String loginId = loginRequest.loginId() != null ? loginRequest.loginId() : "";
			String password = loginRequest.password() != null ? loginRequest.password() : "";
			return getAuthenticationManager()
					.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(loginId, password));
		} catch (IOException e) {
			throw new org.springframework.security.authentication.BadCredentialsException(
					"Invalid login request body", e);
		}
	}

	/** 인증 성공 — SAS 흐름이면 세션 저장 + redirect, 직접 흐름이면 JWT 발급 */
	@Override
	protected void successfulAuthentication(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain chain,
			Authentication authResult)
			throws IOException {

		SavedRequest savedRequest = requestCache.getRequest(request, response);
		boolean isSasFlow =
				savedRequest != null && savedRequest.getRedirectUrl().contains("/oauth2/authorize");

		if (isSasFlow) {
			// SAS Authorization Code + PKCE 흐름
			// SecurityContext를 세션에 저장 → SAS가 인증 감지 → code 발급 → redirect_uri로 리다이렉트
			log.debug("SAS Authorization Code 흐름 감지 — 세션에 인증 저장");
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(authResult);
			SecurityContextHolder.setContext(context);
			sessionContextRepository.saveContext(context, request, response);

			try {
				savedRequestHandler.onAuthenticationSuccess(request, response, authResult);
			} catch (Exception e) {
				log.error("SAS 리다이렉트 실패", e);
				writeError(response, "AUTHORIZATION_REDIRECT_FAILED", "인증 후 리다이렉트에 실패했습니다.");
			}
			return;
		}

		// 직접 JWT 흐름
		MemberUserDetails userDetails = (MemberUserDetails) authResult.getPrincipal();
		TokenPair tokens = loginTokenService.issue(userDetails.getMember());

		String clientType = request.getHeader(CLIENT_TYPE_HEADER);
		boolean isApp = "APP".equalsIgnoreCase(clientType);

		LoginResponse body;
		if (isApp) {
			body =
					LoginResponse.app(tokens.accessToken(), tokens.accessExpiredAt(), tokens.refreshToken());
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
		writeError(response, "INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다.");
	}

	private void writeError(HttpServletResponse response, String code, String message)
			throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		Map<String, Object> body = new HashMap<>();
		body.put("errorCode", code);
		body.put("message", message);
		body.put("timestamp", LocalDateTime.now().toString());
		body.put("fieldErrors", null);
		objectMapper.writeValue(response.getWriter(), body);
	}

	private record LoginRequest(String loginId, String password) {}
}
