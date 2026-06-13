package com.econo.auth.api.presentation.controller;

import com.econo.auth.api.presentation.docs.MemberInfoApiDocs;
import com.econo.auth.api.presentation.dto.MemberInfoResponse;
import com.econo.auth.api.presentation.dto.MemberQueryRequest;
import com.econo.auth.member.application.domain.Member;
import com.econo.auth.member.application.usecase.MemberQueryUseCase;
import jakarta.validation.Valid;
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
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberInfoController implements MemberInfoApiDocs {

	private final MemberQueryUseCase memberQueryUseCase;

	@Override
	@PostMapping("/batch")
	public ResponseEntity<List<MemberInfoResponse>> queryMembers(
			@Valid @RequestBody MemberQueryRequest request) {
		List<Member> members = memberQueryUseCase.findAllByIds(request.ids());
		return ResponseEntity.ok(members.stream().map(MemberInfoResponse::from).toList());
	}
}
