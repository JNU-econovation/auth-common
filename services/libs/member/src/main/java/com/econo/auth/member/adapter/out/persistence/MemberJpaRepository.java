package com.econo.auth.member.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** members 테이블 Spring Data JPA 리포지토리 */
public interface MemberJpaRepository extends JpaRepository<MemberJpaEntity, Long> {

	Optional<MemberJpaEntity> findByLoginId(String loginId);

	boolean existsByLoginId(String loginId);

	List<MemberJpaEntity> findAllByIdIn(List<Long> ids);

	List<MemberJpaEntity> findAllBy(Pageable pageable);

	List<MemberJpaEntity> findAllByRole(String role, Pageable pageable);

	long countByRole(String role);
}
