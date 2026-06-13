package com.econo.auth.api.presentation.docs;

import com.econo.auth.api.presentation.dto.ErrorResponse;
import com.econo.auth.api.presentation.dto.LoginResponse;
import com.econo.auth.api.presentation.dto.ReissueRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;

/** ReissueController 의 Swagger 문서 정의. 어노테이션만 보유하며 메서드 규격은 컨트롤러와 동일하다. */
@Tag(name = "Auth")
public interface ReissueApiDocs {

	@Operation(
			summary = "AT/RT 재발급",
			description =
					"Refresh Token으로 새 Access Token과 Refresh Token을 발급한다.\n\n"
							+ "**WEB** (`Client-Type: WEB`, 기본): RT를 HttpOnly 쿠키(`rt`)에서 읽음. 새 RT를 쿠키로 반환.\n"
							+ "**APP** (`Client-Type: APP`): RT를 요청 body(`refreshToken`)에서 읽음. 새 RT도 body로 반환.")
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "재발급 성공",
				content = @Content(schema = @Schema(implementation = LoginResponse.class))),
		@ApiResponse(
				responseCode = "401",
				description = "RT 없음, 만료, 또는 access token으로 재발급 시도",
				content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	ResponseEntity<?> reissue(
			String clientType,
			ReissueRequest body,
			HttpServletRequest request,
			HttpServletResponse response);

	@Operation(
			summary = "로그아웃",
			description =
					"**WEB**: `at`/`rt` HttpOnly 쿠키를 Max-Age=0으로 만료시킨다.\n**APP**: 클라이언트가 RT를 직접 삭제한다.")
	@ApiResponse(responseCode = "200", description = "로그아웃 성공 (멱등 — RT 없어도 200)")
	ResponseEntity<Void> logout(String clientType, HttpServletResponse response);
}
