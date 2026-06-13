package com.econo.auth.login.application.service;

import com.econo.auth.login.application.domain.DecodedToken;
import com.econo.auth.login.application.domain.TokenModel;
import com.econo.auth.login.application.repository.TokenDecoder;
import com.econo.auth.login.application.repository.TokenEncoder;
import com.econo.auth.login.application.usecase.LoginTokenUseCase;
import com.econo.auth.login.exception.InvalidTokenException;
import com.econo.auth.login.exception.WrongTokenTypeException;
import com.econo.auth.member.application.domain.Member;
import com.econo.auth.member.application.repository.MemberRepository;
import com.econo.auth.member.exception.MemberNotFoundException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 로그인 시 AT(Access Token) + RT(Refresh Token)를 발급하는 서비스
 *
 * <p>{@link TokenEncoder} 및 {@link TokenDecoder} 출력 포트를 통해 JWT 인코딩·디코딩을 수행한다. Spring Security
 * OAuth2 타입 의존 없음. AT에는 Gateway의 {@code BearerToPassportFilter}가 읽는 Passport 클레임이 포함된다.
 */
@Service
public class LoginTokenService implements LoginTokenUseCase {

	private static final String TOKEN_TYPE_CLAIM = "token_type";
	private static final String ACCESS = "access";
	private static final String REFRESH = "refresh";

	private final TokenEncoder encoder;
	private final TokenDecoder decoder;
	private final MemberRepository memberRepository;
	private final String issuer;
	private final long atExpirySeconds;
	private final long rtExpirySeconds;

	public LoginTokenService(
			TokenEncoder encoder,
			TokenDecoder decoder,
			MemberRepository memberRepository,
			@Value("${AUTH_ISSUER_URI:http://localhost:8081}") String issuer,
			@Value("${auth.token.at-expiry-seconds:3600}") long atExpirySeconds,
			@Value("${auth.token.rt-expiry-seconds:2592000}") long rtExpirySeconds) {
		this.encoder = encoder;
		this.decoder = decoder;
		this.memberRepository = memberRepository;
		this.issuer = issuer;
		this.atExpirySeconds = atExpirySeconds;
		this.rtExpirySeconds = rtExpirySeconds;
	}

	/** Member 객체로 AT + RT 발급 — 로그인 시 사용 */
	@Override
	public TokenPair issue(Member member) {
		Instant now = Instant.now();
		String at = encodeAt(member, now);
		String rt = encodeRt(member, now);
		return new TokenPair(at, now.plusSeconds(atExpirySeconds).toEpochMilli(), rt);
	}

	/**
	 * memberId로 회원을 조회해 AT + RT 재발급 — 재발급 요청 시 사용.
	 *
	 * <p>RT 서명 검증 및 token_type 확인은 {@link #verifyRefreshTokenAndGetMemberId(String)}에서 먼저 수행하고
	 * memberId만 전달된다.
	 *
	 * @param memberId 회원 PK
	 * @return 새 토큰 페어
	 * @throws MemberNotFoundException 회원이 존재하지 않을 때 (JWT 유효하지만 회원 탈퇴 케이스)
	 */
	@Override
	public TokenPair reissue(Long memberId) {
		Member member =
				memberRepository.findById(memberId).orElseThrow(() -> MemberNotFoundException.of(memberId));
		return issue(member);
	}

	/**
	 * RT를 검증하고 회원 ID를 반환한다
	 *
	 * @param rawRt Refresh Token 문자열
	 * @return 회원 ID
	 * @throws InvalidTokenException 서명 불일치, 만료 등 디코딩 실패 시 (TokenDecoder에서 전파)
	 * @throws WrongTokenTypeException token_type이 refresh가 아닐 때
	 */
	@Override
	public Long verifyRefreshTokenAndGetMemberId(String rawRt)
			throws InvalidTokenException, WrongTokenTypeException {
		DecodedToken decoded = decoder.decode(rawRt);
		String tokenType = (String) decoded.claims().get(TOKEN_TYPE_CLAIM);
		if (!REFRESH.equals(tokenType)) {
			throw WrongTokenTypeException.of(tokenType);
		}
		return Long.valueOf(decoded.subject());
	}

	/** AT TokenModel 조립 후 encoder.encode() 호출 */
	private String encodeAt(Member member, Instant now) {
		Map<String, Object> claims = new HashMap<>();
		claims.put("memberId", member.getId());
		claims.put("loginId", member.getLoginId());
		claims.put("name", member.getName());
		claims.put("generation", member.getGeneration());
		claims.put("status", member.getStatus().name());
		claims.put("roles", List.of(member.getRole()));
		claims.put(TOKEN_TYPE_CLAIM, ACCESS);

		TokenModel model =
				new TokenModel(
						issuer, String.valueOf(member.getId()), now, now.plusSeconds(atExpirySeconds), claims);
		return encoder.encode(model);
	}

	/** RT TokenModel 조립 후 encoder.encode() 호출 */
	private String encodeRt(Member member, Instant now) {
		Map<String, Object> claims = new HashMap<>();
		claims.put(TOKEN_TYPE_CLAIM, REFRESH);

		TokenModel model =
				new TokenModel(
						issuer, String.valueOf(member.getId()), now, now.plusSeconds(rtExpirySeconds), claims);
		return encoder.encode(model);
	}
}
