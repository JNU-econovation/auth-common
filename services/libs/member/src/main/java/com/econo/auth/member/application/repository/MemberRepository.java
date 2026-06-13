package com.econo.auth.member.application.repository;

import com.econo.auth.member.application.domain.Member;
import java.util.List;
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

	/**
	 * ID로 회원 조회
	 *
	 * @param id 회원 PK
	 * @return 조회된 회원 (없으면 Optional.empty())
	 */
	Optional<Member> findById(Long id);

	/**
	 * loginId 존재 여부 확인
	 *
	 * @param loginId 로그인 아이디
	 * @return 존재하면 true
	 */
	boolean existsByLoginId(String loginId);

	/**
	 * ID 목록으로 회원 일괄 조회
	 *
	 * @param ids 조회할 회원 ID 목록
	 * @return 조회된 회원 목록 (없는 ID는 제외됨)
	 */
	List<Member> findAllByIds(List<Long> ids);

	/**
	 * 회원 역할(role) 업데이트
	 *
	 * @param memberId 회원 PK
	 * @param role 변경할 역할 (USER, ADMIN, SUPER_ADMIN)
	 */
	void updateRole(Long memberId, String role);

	/**
	 * 회원 목록 페이지 조회
	 *
	 * @param page 페이지 번호 (0부터)
	 * @param size 페이지 크기
	 * @param role null이면 전체 조회, 값이 있으면 해당 역할만 필터
	 * @return 회원 목록
	 */
	List<Member> findPaged(int page, int size, String role);

	/**
	 * 전체 회원 수 (역할 필터 선택)
	 *
	 * @param role null이면 전체, 값이 있으면 해당 역할만
	 * @return 회원 수
	 */
	long count(String role);

	/**
	 * 특정 역할 보유 회원 수
	 *
	 * @param role 역할
	 * @return 회원 수
	 */
	long countByRole(String role);
}
