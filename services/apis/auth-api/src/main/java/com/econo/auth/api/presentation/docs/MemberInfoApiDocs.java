package com.econo.auth.api.presentation.docs;

import com.econo.auth.api.presentation.controller.MemberInfoController.MemberInfoResponse;
import com.econo.auth.api.presentation.controller.MemberInfoController.MemberQueryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;

/** MemberInfoController 의 Swagger 문서 정의. 어노테이션만 보유하며 메서드 규격은 컨트롤러와 동일하다. */
@Tag(name = "Member")
public interface MemberInfoApiDocs {

	@Operation(
			summary = "회원 정보 조회 (단건/다건 통합)",
			description =
					"IDs 목록으로 회원 정보를 조회한다. 단건도 동일한 엔드포인트를 사용한다.\n\n"
							+ "```json\n"
							+ "// 단건\n"
							+ "{ \"ids\": [42] }\n\n"
							+ "// 다건\n"
							+ "{ \"ids\": [1, 2, 42] }\n"
							+ "```\n\n"
							+ "존재하지 않는 ID는 결과에서 조용히 제외된다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "조회 성공 (결과가 0개여도 200)"),
		@ApiResponse(responseCode = "400", description = "ids 빈 배열 또는 최대 개수 초과", content = @Content)
	})
	ResponseEntity<List<MemberInfoResponse>> queryMembers(MemberQueryRequest request);
}
