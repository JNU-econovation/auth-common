package com.econo.auth.api.config;

import com.econo.auth.api.security.MemberUserDetails;
import com.econo.auth.api.security.MemberUserDetailsMixin;
import com.econo.auth.core.member.domain.Member;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.jackson2.SecurityJackson2Modules;

/**
 * Spring Session JDBC + SAS Authorization Code 흐름을 위한 Jackson 보안 설정
 *
 * <p>SecurityContext가 세션에 JSON으로 저장될 때, MemberUserDetails가 Jackson 허용 목록에 없어 역직렬화 실패하는 문제를 해결한다.
 */
@Configuration
public class SecurityJacksonConfig {

	@Autowired
	void configureObjectMapper(ObjectMapper objectMapper) {
		// Spring Security 기본 모듈 등록
		objectMapper.registerModules(SecurityJackson2Modules.getModules(getClass().getClassLoader()));

		// 커스텀 클래스 믹스인 등록
		objectMapper.addMixIn(MemberUserDetails.class, MemberUserDetailsMixin.class);
		objectMapper.addMixIn(Member.class, MemberMixin.class);
	}

	/** Member 도메인 Jackson 믹스인 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
	@JsonAutoDetect(
			fieldVisibility = JsonAutoDetect.Visibility.ANY,
			getterVisibility = JsonAutoDetect.Visibility.NONE,
			isGetterVisibility = JsonAutoDetect.Visibility.NONE)
	@JsonIgnoreProperties(ignoreUnknown = true)
	abstract static class MemberMixin {}
}
