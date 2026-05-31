package com.econo.auth.api.security;

import com.econo.auth.core.member.domain.Member;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Spring Session JDBC Jackson 직렬화를 위한 {@link MemberUserDetails} 믹스인
 *
 * <p>SAS Authorization Code 흐름에서 세션에 SecurityContext를 저장할 때 사용된다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
@JsonAutoDetect(
		fieldVisibility = JsonAutoDetect.Visibility.ANY,
		getterVisibility = JsonAutoDetect.Visibility.NONE,
		isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class MemberUserDetailsMixin {

	@JsonCreator
	MemberUserDetailsMixin(@JsonProperty("member") Member member) {}
}
