package com.econo.auth.api.presentation.dto;

import com.econo.auth.member.application.domain.Member;
import io.swagger.v3.oas.annotations.media.Schema;

/** 어드민 회원 목록의 단건 요약 정보 DTO */
public record MemberSummary(
		@Schema(description = "회원 PK", example = "42") Long memberId,
		@Schema(description = "이름", example = "홍길동") String name,
		@Schema(description = "로그인 아이디", example = "hong42") String loginId,
		@Schema(description = "기수", example = "30") Integer generation,
		@Schema(description = "활동 상태 (AM/RM/CM/OB)", example = "AM") String status,
		@Schema(description = "역할 (USER/ADMIN/SUPER_ADMIN)", example = "USER") String role) {

	/**
	 * {@link Member} 도메인 객체를 {@link MemberSummary} DTO로 변환한다.
	 *
	 * @param member 변환할 회원 도메인 객체
	 * @return 변환된 MemberSummary DTO
	 */
	public static MemberSummary from(Member member) {
		return new MemberSummary(
				member.getId(),
				member.getName(),
				member.getLoginId(),
				member.getGeneration(),
				member.getStatus().name(),
				member.getRole());
	}
}
