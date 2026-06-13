package com.econo.auth.api.presentation.dto;

import com.econo.auth.member.application.domain.MemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 회원 가입 요청 DTO */
public record SignupRequest(
		@NotBlank @Size(min = 1, max = 50) @Schema(description = "이름", example = "홍길동") String name,
		@NotBlank
				@Pattern(regexp = "^[a-zA-Z0-9\\-_.]{3,19}$")
				@Schema(description = "로그인 아이디 (영문·숫자·-_.만 허용, 3~19자)", example = "hong42")
				String loginId,
		@NotBlank @Size(min = 8, max = 19) @Schema(description = "비밀번호 (8~19자)", example = "P@ssword1")
				String password,
		@NotNull @Min(1) @Max(99) @Schema(description = "기수 (1~99)", example = "30") Integer generation,
		@NotNull @Schema(description = "활동 상태 (AM/RM/CM/OB)", example = "AM") MemberStatus status) {}
