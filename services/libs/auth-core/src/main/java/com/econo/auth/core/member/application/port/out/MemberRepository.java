package com.econo.auth.core.member.application.port.out;

import com.econo.auth.core.member.domain.Member;
import java.util.Optional;

/** 회원 영속성 조작 아웃바운드 포트 */
public interface MemberRepository {

	/**
	 * 회원 저장
	 *
	 * @param member 저장할 회원
	 */
	void save(Member member);

	/**
	 * loginId로 회원 조회
	 *
	 * @param loginId 로그인 아이디
	 * @return 조회된 회원 (없으면 Optional.empty())
	 */
	Optional<Member> findByLoginId(String loginId);

	Optional<Member> findById(Long id);

	/**
	 * loginId 존재 여부 확인
	 *
	 * @param loginId 로그인 아이디
	 * @return 존재하면 true
	 */
	boolean existsByLoginId(String loginId);
}
