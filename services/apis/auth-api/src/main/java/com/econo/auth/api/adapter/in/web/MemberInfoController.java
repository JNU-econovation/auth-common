package com.econo.auth.api.adapter.in.web;

import com.econo.auth.core.member.application.port.out.MemberRepository;
import com.econo.auth.core.member.domain.Member;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 정보 조회 API — 내부 서비스 간 회원 정보 공유용
 *
 * <p>단건/다건 모두 {@code GET /api/v1/members?ids=1,2,3} 하나로 처리한다. 없는 ID는 결과에서 조용히 제외된다.
 *
 * <pre>
 * 단건: GET /api/v1/members?ids=42
 * 다건: GET /api/v1/members?ids=1,2,42
 * </pre>
 *
 * <p>Gateway가 AT를 검증하고 X-User-Passport를 주입한 이후 도달하는 엔드포인트이므로 별도 인증 처리가 없다.
 */
@Tag(name = "Members — Info", description = "회원 정보 조회 API (Gateway 인증 후 내부 서비스 호출용)")
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberInfoController {

	private final MemberRepository memberRepository;

	@Operation(
			summary = "회원 정보 조회 (단건/다건 통합)",
			description =
					"IDs 목록으로 회원 정보를 조회한다. 단건도 동일한 엔드포인트를 사용한다.\n\n"
							+ "- 단건: `GET /api/v1/members?ids=42`\n"
							+ "- 다건: `GET /api/v1/members?ids=1,2,42`\n\n"
							+ "존재하지 않는 ID는 결과에서 조용히 제외된다. 중복 ID는 한 번만 포함된다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공 (결과가 0개여도 200)"),
		@ApiResponse(responseCode = "400", description = "ids 파라미터 없음", content = @Content)
	})
	@GetMapping
	public ResponseEntity<List<MemberInfoResponse>> getMembers(
			@Parameter(description = "조회할 회원 ID 목록 (쉼표 구분, 예: 1,2,42)", required = true) @RequestParam
					List<Long> ids) {
		List<Member> members = memberRepository.findAllByIds(ids);
		return ResponseEntity.ok(members.stream().map(MemberInfoResponse::from).toList());
	}

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
}
