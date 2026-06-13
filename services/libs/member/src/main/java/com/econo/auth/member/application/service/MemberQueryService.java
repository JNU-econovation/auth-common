package com.econo.auth.member.application.service;

import com.econo.auth.member.application.domain.Member;
import com.econo.auth.member.application.repository.MemberRepository;
import com.econo.auth.member.application.usecase.MemberQueryUseCase;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MemberQueryUseCase 구현체 — MemberRepository 포트에 단순 위임 */
@Service
@RequiredArgsConstructor
public class MemberQueryService implements MemberQueryUseCase {

	private final MemberRepository memberRepository;

	@Override
	@Transactional(readOnly = true)
	public Optional<Member> findByLoginId(String loginId) {
		return memberRepository.findByLoginId(loginId);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Member> findById(Long memberId) {
		return memberRepository.findById(memberId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Member> findAllByIds(List<Long> ids) {
		return memberRepository.findAllByIds(ids);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Member> findPaged(int page, int size, String role) {
		return memberRepository.findPaged(page, size, role);
	}

	@Override
	@Transactional(readOnly = true)
	public long count(String role) {
		return memberRepository.count(role);
	}

	@Override
	@Transactional(readOnly = true)
	public long countByRole(String role) {
		return memberRepository.countByRole(role);
	}

	@Override
	@Transactional
	public void updateRole(Long memberId, String role) {
		memberRepository.updateRole(memberId, role);
	}
}
