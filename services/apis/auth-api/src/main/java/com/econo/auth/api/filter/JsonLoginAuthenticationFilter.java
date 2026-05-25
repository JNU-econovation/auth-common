package com.econo.auth.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * JSON 자격증명 수신 → 서버 세션 수립 커스텀 인증 필터
 *
 * <p>{@code POST /api/v1/auth/login} 요청에서 JSON body({@code loginId}, {@code password})를 파싱하고,
 * {@link AuthenticationManager}에 위임하여 인증 성공 시 Spring Session에 {@code SecurityContext}를 저장한다.
 *
 * <h2>성공 응답</h2>
 *
 * <p>{@code 200 OK}, 바디 없음, {@code Set-Cookie: SESSION=...}
 *
 * <h2>실패 응답</h2>
 *
 * <p>{@code 401 Unauthorized}, JSON body {@code
 * {"errorCode":"INVALID_CREDENTIALS","message":"..."}}
 */
@Slf4j
public class JsonLoginAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

	private static final AntPathRequestMatcher LOGIN_MATCHER =
			new AntPathRequestMatcher("/api/v1/auth/login", "POST");

	private final ObjectMapper objectMapper;

	/**
	 * JsonLoginAuthenticationFilter 생성자
	 *
	 * @param authenticationManager Spring Security AuthenticationManager
	 * @param objectMapper Jackson ObjectMapper
	 */
	public JsonLoginAuthenticationFilter(
			AuthenticationManager authenticationManager, ObjectMapper objectMapper) {
		super(LOGIN_MATCHER, authenticationManager);
		this.objectMapper = objectMapper;
		// Spring Security 6.x: SecurityContext를 세션에 명시적으로 저장하도록 설정
		setSecurityContextRepository(new HttpSessionSecurityContextRepository());
	}

	/**
	 * JSON 바디에서 자격증명 파싱 후 인증 시도
	 *
	 * @param request HTTP 요청
	 * @param response HTTP 응답
	 * @return 인증된 {@link Authentication}
	 * @throws AuthenticationException 인증 실패 시
	 */
	@Override
	public Authentication attemptAuthentication(
			HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {
		try {
			LoginRequest loginRequest =
					objectMapper.readValue(request.getInputStream(), LoginRequest.class);
			String loginId = loginRequest.loginId() != null ? loginRequest.loginId() : "";
			String password = loginRequest.password() != null ? loginRequest.password() : "";
			UsernamePasswordAuthenticationToken authToken =
					UsernamePasswordAuthenticationToken.unauthenticated(loginId, password);
			return getAuthenticationManager().authenticate(authToken);
		} catch (IOException e) {
			log.warn("Failed to parse login request body: {}", e.getMessage());
			throw new org.springframework.security.authentication.BadCredentialsException(
					"Invalid login request body", e);
		}
	}

	/**
	 * 인증 성공 처리 — 200 OK + 세션 쿠키
	 *
	 * @param request HTTP 요청
	 * @param response HTTP 응답
	 * @param chain 필터 체인
	 * @param authResult 인증 결과
	 */
	@Override
	protected void successfulAuthentication(
			HttpServletRequest request,
			HttpServletResponse response,
			jakarta.servlet.FilterChain chain,
			Authentication authResult)
			throws IOException, jakarta.servlet.ServletException {
		// SecurityContextRepository(HttpSession)에 저장 — 세션 수립
		super.successfulAuthentication(request, response, chain, authResult);
		response.setStatus(HttpServletResponse.SC_OK);
	}

	/**
	 * 인증 실패 처리 — 401 Unauthorized + JSON 에러 응답
	 *
	 * @param request HTTP 요청
	 * @param response HTTP 응답
	 * @param failed 인증 예외
	 */
	@Override
	protected void unsuccessfulAuthentication(
			HttpServletRequest request, HttpServletResponse response, AuthenticationException failed)
			throws IOException {
		log.warn("Login failed: {}", failed.getMessage());
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");

		// Map.of는 null 값 불허 — HashMap 사용하여 JSON null(fieldErrors: null) 직렬화
		Map<String, Object> errorBody = new HashMap<>();
		errorBody.put("errorCode", "INVALID_CREDENTIALS");
		errorBody.put("message", "아이디 또는 비밀번호가 올바르지 않습니다.");
		errorBody.put("timestamp", LocalDateTime.now().toString());
		errorBody.put("fieldErrors", null);

		objectMapper.writeValue(response.getWriter(), errorBody);
	}

	/** JSON 로그인 요청 DTO (내부 private record) */
	private record LoginRequest(String loginId, String password) {}
}
