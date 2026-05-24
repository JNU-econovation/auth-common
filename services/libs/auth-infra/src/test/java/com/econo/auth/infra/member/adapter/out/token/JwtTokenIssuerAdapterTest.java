package com.econo.auth.infra.member.adapter.out.token;

import static org.assertj.core.api.Assertions.*;

import com.econo.auth.core.member.domain.Member;
import com.econo.auth.core.member.domain.MemberStatus;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JwtTokenIssuerAdapterTest {

	private static final String JWT_SECRET =
			"test-secret-key-which-is-long-enough-for-hmac-sha256-algorithm-32bytes";
	private static final long EXPIRY_SECONDS = 3600L;

	private JwtTokenIssuerAdapter jwtTokenIssuerAdapter;

	@BeforeEach
	void setUp() {
		jwtTokenIssuerAdapter = new JwtTokenIssuerAdapter(JWT_SECRET, EXPIRY_SECONDS);
	}

	/** Claims 파싱 헬퍼 */
	private Claims parseClaims(String jwt) {
		SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
		return Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt).getPayload();
	}

	@Nested
	@DisplayName("JWT 발급 테스트")
	class JwtIssueTest {

		@Test
		@DisplayName("Member로 JWT 발급 시 올바른 클레임이 설정된다")
		void issueJwtWithCorrectClaims() {
			// given
			Member member =
					Member.create("홍길동", "honggildong", "$2a$12$hashedPassword", 32, MemberStatus.AM);

			// when
			String jwt = jwtTokenIssuerAdapter.issue(member);

			// then
			assertThat(jwt).isNotBlank();
			Claims claims = parseClaims(jwt);
			assertThat(claims.getSubject()).isNotNull(); // sub = memberId
			assertThat(claims.get("loginId", String.class)).isEqualTo("honggildong");
			assertThat(claims.get("name", String.class)).isEqualTo("홍길동");
			assertThat(claims.get("generation", Integer.class)).isEqualTo(32);
			assertThat(claims.get("status", String.class)).isEqualTo("AM");
			assertThat(claims.get("roles", List.class)).contains("USER");
			assertThat(claims.getIssuedAt()).isNotNull();
			assertThat(claims.getExpiration()).isNotNull();
		}

		@Test
		@DisplayName("JWT exp는 iat보다 expiry-seconds만큼 미래")
		void expirationIsCorrect() {
			// given
			Member member =
					Member.create("홍길동", "honggildong", "$2a$12$hashedPassword", 32, MemberStatus.AM);

			// when
			String jwt = jwtTokenIssuerAdapter.issue(member);

			// then
			Claims claims = parseClaims(jwt);
			long diffSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
			assertThat(diffSeconds).isEqualTo(EXPIRY_SECONDS);
		}

		@Test
		@DisplayName("roles 클레임은 항상 [USER] 고정")
		void rolesIsAlwaysUser() {
			// given
			Member member =
					Member.create("홍길동", "honggildong", "$2a$12$hashedPassword", 32, MemberStatus.OB);

			// when
			String jwt = jwtTokenIssuerAdapter.issue(member);

			// then
			Claims claims = parseClaims(jwt);
			@SuppressWarnings("unchecked")
			List<String> roles = (List<String>) claims.get("roles", List.class);
			assertThat(roles).containsExactly("USER");
		}
	}
}
