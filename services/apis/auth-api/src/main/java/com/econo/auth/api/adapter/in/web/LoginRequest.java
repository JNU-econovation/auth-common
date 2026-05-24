package com.econo.auth.api.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/** 로그인 요청 DTO */
public record LoginRequest(@NotBlank String loginId, @NotBlank String password) {}
