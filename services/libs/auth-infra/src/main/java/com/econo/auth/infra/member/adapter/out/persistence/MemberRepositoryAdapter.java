package com.econo.auth.infra.member.adapter.out.persistence;

import com.econo.auth.core.member.application.port.out.MemberRepository;
import com.econo.auth.core.member.domain.Member;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** MemberRepository 포트 구현체 */
@Component
@RequiredArgsConstructor
public class MemberRepositoryAdapter implements MemberRepository {

	private final MemberJpaRepository memberJpaRepository;

	@Override
	@Transactional
	public void save(Member member) {
		memberJpaRepository.save(MemberJpaEntity.from(member));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Member> findByLoginId(String loginId) {
		return memberJpaRepository.findByLoginId(loginId).map(MemberJpaEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean existsByLoginId(String loginId) {
		return memberJpaRepository.existsByLoginId(loginId);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Member> findById(Long id) {
		return memberJpaRepository.findById(id).map(MemberJpaEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Member> findAllByIds(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return memberJpaRepository.findAllByIdIn(ids).stream().map(MemberJpaEntity::toDomain).toList();
	}
}
