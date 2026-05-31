package com.econo.auth.member.infra.adapter.out.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** members 테이블 Spring Data JPA 리포지토리 */
public interface MemberJpaRepository extends JpaRepository<MemberJpaEntity, Long> {

	/**
	 * loginId로 회원 조회
	 *
	 * @param loginId 로그인 아이디
	 * @return 조회된 엔티티 (없으면 Optional.empty())
	 */
	Optional<MemberJpaEntity> findByLoginId(String loginId);

	/**
	 * loginId 존재 여부 확인
	 *
	 * @param loginId 로그인 아이디
	 * @return 존재하면 true
	 */
	boolean existsByLoginId(String loginId);
}
