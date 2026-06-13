package com.econo.auth.api.presentation.docs;

import com.econo.auth.api.presentation.dto.HealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** RootController 의 Swagger 문서 정의. 어노테이션만 보유하며 메서드 규격은 컨트롤러와 동일하다. */
@Tag(name = "Health")
public interface RootApiDocs {

	@Operation(summary = "루트 헬스체크", description = "애플리케이션 이름, 기동 시각(startedAt), uptime을 반환한다.")
	HealthResponse root();
}
