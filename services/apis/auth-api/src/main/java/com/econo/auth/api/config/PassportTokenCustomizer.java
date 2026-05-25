package com.econo.auth.api.config;

import com.econo.auth.api.security.MemberUserDetails;
import com.econo.auth.core.member.domain.Member;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Passport 커스텀 클레임 주입 OAuth2TokenCustomizer
 *
 * <p>SAS가 Access Token 및 ID Token 생성 시 자동으로 호출하여 Passport 커스텀 클레임을 주입한다. 주입되는 클레임 목록:
 *
 * <ul>
 *   <li>{@code memberId}: Long — 회원 식별자
 *   <li>{@code loginId}: String — 로그인 아이디
 *   <li>{@code name}: String — 회원 이름
 *   <li>{@code generation}: Integer — 기수
 *   <li>{@code status}: String — 활동 상태 (AM/RM/CM/OB)
 *   <li>{@code roles}: List&lt;String&gt; — 역할 목록 (현재 ["USER"] 고정)
 * </ul>
 */
@Slf4j
@Configuration
public class PassportTokenCustomizer {

	/**
	 * OAuth2TokenCustomizer 빈 등록 — SAS가 자동 감지하여 토큰 생성 시 호출
	 *
	 * @return {@link OAuth2TokenCustomizer} 구현체
	 */
	@Bean
	public OAuth2TokenCustomizer<JwtEncodingContext> passportTokenCustomizer() {
		return context -> {
			String tokenType = context.getTokenType().getValue();
			boolean isAccessToken =
					org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN
							.getValue()
							.equals(tokenType);
			boolean isIdToken = OidcParameterNames.ID_TOKEN.equals(tokenType);

			if (!isAccessToken && !isIdToken) {
				return;
			}

			Object principal = context.getPrincipal().getPrincipal();
			if (!(principal instanceof MemberUserDetails memberUserDetails)) {
				log.warn("Principal is not MemberUserDetails: {}", principal.getClass().getName());
				return;
			}

			Member member = memberUserDetails.getMember();
			// sub를 String(memberId)로 설정 — SAS 기본은 loginId(username)를 sub로 사용하므로
			// PassportBuilder의 Long.valueOf(jwt.getSubject()) 파싱이 NumberFormatException을 유발할 수 있음
			context
					.getClaims()
					.subject(String.valueOf(member.getId()))
					.claim("memberId", member.getId())
					.claim("loginId", member.getLoginId())
					.claim("name", member.getName())
					.claim("generation", member.getGeneration())
					.claim("status", member.getStatus().name())
					.claim("roles", List.of("USER"));
		};
	}
}
