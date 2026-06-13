package com.econo.auth.member.persistence.repository;

import com.econo.auth.member.application.domain.Member;
import com.econo.auth.member.application.repository.MemberRepository;
import com.econo.auth.member.persistence.entity.MemberJpaEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

	@Override
	@Transactional
	public void updateRole(Long memberId, String role) {
		memberJpaRepository
				.findById(memberId)
				.ifPresent(entity -> memberJpaRepository.save(entity.withRole(role)));
	}

	@Override
	@Transactional(readOnly = true)
	public List<Member> findPaged(int page, int size, String role) {
		PageRequest pageable = PageRequest.of(page, size, Sort.by("id").ascending());
		if (role == null || role.isBlank()) {
			return memberJpaRepository.findAllBy(pageable).stream()
					.map(MemberJpaEntity::toDomain)
					.toList();
		}
		return memberJpaRepository.findAllByRole(role, pageable).stream()
				.map(MemberJpaEntity::toDomain)
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public long count(String role) {
		if (role == null || role.isBlank()) {
			return memberJpaRepository.count();
		}
		return memberJpaRepository.countByRole(role);
	}

	@Override
	@Transactional(readOnly = true)
	public long countByRole(String role) {
		return memberJpaRepository.countByRole(role);
	}
}
