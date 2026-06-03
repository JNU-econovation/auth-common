package com.econo.auth.api.adapter.in.web;

import com.econo.auth.member.domain.MemberStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 회원 가입 요청 DTO */
public record SignupRequest(
		@NotBlank @Size(min = 1, max = 50) String name,
		@NotBlank @Pattern(regexp = "^[a-zA-Z0-9\\-_.]{3,19}$") String loginId,
		@NotBlank @Size(min = 8, max = 19) String password,
		@NotNull @Min(1) @Max(99) Integer generation,
		@NotNull MemberStatus status) {}
