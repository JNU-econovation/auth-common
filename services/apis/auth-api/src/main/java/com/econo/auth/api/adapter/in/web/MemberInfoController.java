package com.econo.auth.api.adapter.in.web;

import com.econo.auth.core.member.application.port.out.MemberRepository;
import com.econo.auth.core.member.domain.Member;
import com.econo.auth.core.member.exception.MemberNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 정보 조회 API — 내부 서비스 간 회원 정보 공유용
 *
 * <p>Gateway가 AT를 검증하고 X-User-Passport 헤더를 주입한 이후 도달하는 엔드포인트이다. 다른 서비스(EEOS-BE 등)에서 타 회원 정보가 필요할 때
 * Gateway를 통해 이 API를 호출한다.
 *
 * <pre>
 * 단건:  GET  /api/v1/members/{memberId}
 * 다건:  POST /api/v1/members/batch  { "memberIds": [1, 2, 3] }
 * </pre>
 */
@Tag(name = "Members — Info", description = "회원 정보 조회 API (Gateway 인증 후 내부 서비스 호출용)")
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberInfoController {

	private final MemberRepository memberRepository;

	/**
	 * 단건 회원 정보 조회
	 *
	 * @param memberId 조회할 회원 ID
	 */
	@Operation(
			summary = "단건 회원 정보 조회",
			description = "memberId로 회원 정보를 조회한다. Gateway를 통해 인증된 요청만 허용 (X-User-Passport 필수).")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공"),
		@ApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND", content = @Content)
	})
	@GetMapping("/{memberId}")
	public ResponseEntity<MemberInfoResponse> getMember(
			@Parameter(description = "조회할 회원 ID") @PathVariable Long memberId) {
		Member member =
				memberRepository
						.findById(memberId)
						.orElseThrow(() -> new MemberNotFoundException(memberId));
		return ResponseEntity.ok(MemberInfoResponse.from(member));
	}

	/**
	 * 다건 회원 정보 일괄 조회
	 *
	 * <p>존재하지 않는 ID는 결과에서 제외된다 (오류 아님). 중복 ID는 한 번만 포함된다.
	 *
	 * @param request 조회할 회원 ID 목록
	 */
	@Operation(
			summary = "다건 회원 정보 일괄 조회",
			description =
					"최대 100개 ID를 한 번에 조회한다. 존재하지 않는 ID는 결과에서 조용히 제외된다.\n\n"
							+ "```json\n"
							+ "{ \"memberIds\": [1, 2, 42] }\n"
							+ "```")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공 (결과가 0개여도 200)"),
		@ApiResponse(responseCode = "400", description = "memberIds 빈 배열", content = @Content)
	})
	@PostMapping("/batch")
	public ResponseEntity<List<MemberInfoResponse>> getMembers(
			@RequestBody MemberBatchRequest request) {
		List<Member> members = memberRepository.findAllByIds(request.memberIds());
		return ResponseEntity.ok(members.stream().map(MemberInfoResponse::from).toList());
	}

	// ── DTO ─────────────────────────────────────────────────────

	/**
	 * 회원 정보 응답 DTO
	 *
	 * @param memberId 회원 PK
	 * @param name 이름
	 * @param loginId 로그인 아이디
	 * @param generation 기수
	 * @param status 활동 상태 (AM/RM/CM/OB)
	 */
	public record MemberInfoResponse(
			Long memberId, String name, String loginId, Integer generation, String status) {

		static MemberInfoResponse from(Member member) {
			return new MemberInfoResponse(
					member.getId(),
					member.getName(),
					member.getLoginId(),
					member.getGeneration(),
					member.getStatus().name());
		}
	}

	/**
	 * 다건 조회 요청 DTO
	 *
	 * @param memberIds 조회할 회원 ID 목록 (최대 100개)
	 */
	public record MemberBatchRequest(@NotEmpty List<Long> memberIds) {}
}
