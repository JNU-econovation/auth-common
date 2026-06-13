package com.econo.auth.api.adapter.in.web;

import com.econo.auth.member.application.port.out.MemberRepository;
import com.econo.auth.member.domain.Member;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 정보 조회 API — 내부 서비스 간 회원 정보 공유용
 *
 * <p>단건/다건 모두 {@code POST /api/v1/members/batch} 하나로 처리한다.
 *
 * <p>GET ?ids= 방식은 URL 길이 제한(서버/프록시마다 2KB~8KB)으로 인해 대량 조회가 불가능하다. body에 ID 목록을 담는 POST를 사용해 이 한계를
 * 우회한다. (Elasticsearch _msearch, Google Batch API와 동일한 패턴)
 *
 * <pre>
 * POST /api/v1/members/batch
 * { "ids": [1, 2, 42] }
 * </pre>
 *
 * <p>단건도 배열에 하나만 담으면 된다: {@code { "ids": [42] }}
 *
 * <p>Gateway가 AT를 검증하고 X-User-Passport를 주입한 이후 도달하는 엔드포인트이므로 별도 인증 처리가 없다.
 */
@Tag(name = "Members — Info", description = "회원 정보 조회 API (Gateway 인증 후 내부 서비스 호출용)")
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberInfoController {

	private static final int MAX_IDS = 1000;

	private final MemberRepository memberRepository;

	@Operation(
			summary = "회원 정보 조회 (단건/다건 통합)",
			description =
					"IDs 목록으로 회원 정보를 조회한다. 단건도 동일한 엔드포인트를 사용한다.\n\n"
							+ "```json\n"
							+ "// 단건\n"
							+ "{ \"ids\": [42] }\n\n"
							+ "// 다건 (최대 "
							+ MAX_IDS
							+ "개)\n"
							+ "{ \"ids\": [1, 2, 42] }\n"
							+ "```\n\n"
							+ "존재하지 않는 ID는 결과에서 조용히 제외된다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공 (결과가 0개여도 200)"),
		@ApiResponse(
				responseCode = "400",
				description = "ids 빈 배열 또는 " + MAX_IDS + "개 초과",
				content = @Content)
	})
	@PostMapping("/batch")
	public ResponseEntity<List<MemberInfoResponse>> queryMembers(
			@Valid @RequestBody MemberQueryRequest request) {
		List<Member> members = memberRepository.findAllByIds(request.ids());
		return ResponseEntity.ok(members.stream().map(MemberInfoResponse::from).toList());
	}

	// ── DTO ─────────────────────────────────────────────────────

	/**
	 * 회원 조회 요청 DTO
	 *
	 * @param ids 조회할 회원 ID 목록 (1개 이상, 최대 {@value MAX_IDS}개)
	 */
	public record MemberQueryRequest(
			@NotEmpty(message = "ids는 1개 이상이어야 합니다.")
					@Size(max = MAX_IDS, message = "한 번에 최대 " + MAX_IDS + "개까지 조회할 수 있습니다.")
					List<Long> ids) {}

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
