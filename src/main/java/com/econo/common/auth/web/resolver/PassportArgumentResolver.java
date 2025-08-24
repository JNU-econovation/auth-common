package com.econo.common.auth.web.resolver;

import com.econo.common.auth.core.passport.Passport;
import com.econo.common.auth.core.passport.PassportException;
import com.econo.common.auth.core.passport.Roles;
import com.econo.common.auth.web.annotation.PassportAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

/**
 * {@code @PassportAuth} 어노테이션이 붙은 Passport 파라미터를 자동으로 주입하는 ArgumentResolver
 *
 * <p>Gateway에서 전달된 X-User-Passport 헤더를 디코딩하여 Passport 객체로 변환하고, 권한 검증, 권한 계층, SpEL 조건 등을 처리합니다.
 *
 * <h3>지원 기능:</h3>
 *
 * <ul>
 *   <li>기본 권한 검증 (String 기반)
 *   <li>권한 계층 지원 (SUPER_ADMIN > ADMIN > MANAGER > USER)
 *   <li>SpEL 표현식 기반 조건부 권한
 *   <li>선택적 인증 (required = false)
 *   <li>만료 검증 (validateExpiry)
 *   <li>복합 권한 조건 (requireAllRoles, includeHigherRoles)
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class PassportArgumentResolver implements HandlerMethodArgumentResolver {

	private static final String USER_PASSPORT_HEADER = "X-User-Passport";
	private final ObjectMapper objectMapper;
	private final ExpressionParser expressionParser = new SpelExpressionParser();

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(PassportAuth.class)
				&& parameter.getParameterType().equals(Passport.class);
	}

	@Override
	public Object resolveArgument(
			MethodParameter parameter,
			ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest,
			WebDataBinderFactory binderFactory) {

		PassportAuth annotation = parameter.getParameterAnnotation(PassportAuth.class);
		if (annotation == null) {
			return null;
		}

		String encodedPassport = extractPassportHeader(webRequest, annotation);
		if (encodedPassport == null) {
			return null;
		}

		try {
			Passport passport = decodePassport(encodedPassport);
			validatePassport(passport, annotation, webRequest);

			return passport;

		} catch (PassportException e) {
			throw e;
		} catch (Exception e) {
			log.error("Failed to resolve Passport from header: {}", e.getMessage());
			throw PassportException.badRequest("Failed to parse passport: " + e.getMessage());
		}
	}

	private String extractPassportHeader(NativeWebRequest webRequest, PassportAuth annotation) {
		HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
		if (request == null) {
			return handleMissingPassport(annotation, "HttpServletRequest is null");
		}

		String encodedPassport = request.getHeader(USER_PASSPORT_HEADER);
		if (!StringUtils.hasText(encodedPassport)) {
			return handleMissingPassport(annotation, "Missing " + USER_PASSPORT_HEADER + " header");
		}

		return encodedPassport;
	}

	private Passport decodePassport(String encodedPassport) {
		try {
			byte[] decodedBytes = Base64.getDecoder().decode(encodedPassport);
			String passportJson = new String(decodedBytes, StandardCharsets.UTF_8);
			return objectMapper.readValue(passportJson, Passport.class);
		} catch (Exception e) {
			throw PassportException.badRequest("Failed to decode passport: " + e.getMessage());
		}
	}

	private void validatePassport(
			Passport passport, PassportAuth annotation, NativeWebRequest webRequest) {
		if (!passport.isValid()) {
			throw PassportException.invalid("passport validation failed");
		}

		if (annotation.validateExpiry() && passport.isExpired()) {
			throw PassportException.expired(passport.getMemberId());
		}

		validateRoles(passport, annotation);
		validateCondition(passport, annotation, webRequest);
	}

	/** Passport가 없을 때 처리 */
	private String handleMissingPassport(PassportAuth annotation, String reason) {
		if (!annotation.required()) {
			return null;
		}

		log.warn("Required Passport is missing: {}", reason);
		throw PassportException.unauthorized("Authentication required: " + reason);
	}

	/** 권한 검증 (권한 계층 지원 포함) */
	private void validateRoles(Passport passport, PassportAuth annotation) {
		String[] requiredRoles = annotation.requiredRoles();
		if (requiredRoles.length == 0) {
			return;
		}

		boolean hasRequiredRole;
		if (annotation.requireAllRoles()) {
			hasRequiredRole = hasAllRoles(passport, requiredRoles, annotation.includeHigherRoles());
		} else {
			hasRequiredRole = hasAnyRole(passport, requiredRoles, annotation.includeHigherRoles());
		}

		if (!hasRequiredRole) {
			String roleCondition = annotation.requireAllRoles() ? "all" : "any";
			String hierarchyNote = annotation.includeHigherRoles() ? " (including higher roles)" : "";
			throw PassportException.forbidden(
					String.format(
							"Member %d lacks required roles (%s of %s)%s",
							passport.getMemberId(),
							roleCondition,
							String.join(", ", requiredRoles),
							hierarchyNote));
		}
	}

	/** 모든 권한 보유 확인 (권한 계층 지원) */
	private boolean hasAllRoles(
			Passport passport, String[] requiredRoles, boolean includeHigherRoles) {
		for (String requiredRole : requiredRoles) {
			if (!hasRole(passport, requiredRole, includeHigherRoles)) {
				return false;
			}
		}
		return true;
	}

	/** 하나 이상의 권한 보유 확인 (권한 계층 지원) */
	private boolean hasAnyRole(
			Passport passport, String[] requiredRoles, boolean includeHigherRoles) {
		for (String requiredRole : requiredRoles) {
			if (hasRole(passport, requiredRole, includeHigherRoles)) {
				return true;
			}
		}
		return false;
	}

	/** 단일 권한 보유 확인 (권한 계층 지원) */
	private boolean hasRole(Passport passport, String requiredRole, boolean includeHigherRoles) {
		if (passport.hasRole(requiredRole)) {
			return true;
		}

		if (includeHigherRoles) {
			for (String userRole : passport.getRoles()) {
				if (Roles.hasHigherOrEqualRole(userRole, requiredRole)) {
					return true;
				}
			}
		}

		return false;
	}

	/** SpEL 조건 검증 */
	private void validateCondition(
			Passport passport, PassportAuth annotation, NativeWebRequest webRequest) {
		String condition = annotation.condition();
		if (!StringUtils.hasText(condition)) {
			return;
		}

		try {
			Expression expression = expressionParser.parseExpression(condition);
			EvaluationContext context = createEvaluationContext(passport, webRequest);

			Boolean result = expression.getValue(context, Boolean.class);
			if (result == null || !result) {
				throw PassportException.forbidden(
						String.format(
								"Member %d does not meet condition: %s", passport.getMemberId(), condition));
			}

		} catch (PassportException e) {
			throw e;
		} catch (Exception e) {
			log.error("Failed to evaluate SpEL condition '{}': {}", condition, e.getMessage());
			throw PassportException.badRequest(
					"Invalid condition expression: " + condition + " - " + e.getMessage());
		}
	}

	/** SpEL 평가를 위한 컨텍스트 생성 */
	private EvaluationContext createEvaluationContext(
			Passport passport, NativeWebRequest webRequest) {
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setRootObject(passport);
		context.setVariable("passport", passport);
		HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
		if (request != null) {
			@SuppressWarnings("unchecked")
			Map<String, String> pathVariables =
					(Map<String, String>)
							request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

			if (pathVariables != null) {
				for (Map.Entry<String, String> entry : pathVariables.entrySet()) {
					Object value = convertPathVariable(entry.getValue());
					context.setVariable(entry.getKey(), value);
				}
			}

			Map<String, String[]> parameterMap = request.getParameterMap();
			for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
				String[] values = entry.getValue();
				Object value = values.length == 1 ? convertPathVariable(values[0]) : values;
				context.setVariable(entry.getKey(), value);
			}
		}

		return context;
	}

	/** Path variable 값을 적절한 타입으로 변환 */
	private Object convertPathVariable(String value) {
		if (value == null) {
			return null;
		}

		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e1) {
			try {
				return Integer.parseInt(value);
			} catch (NumberFormatException e2) {
				try {
					return Double.parseDouble(value);
				} catch (NumberFormatException e3) {
					if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
						return Boolean.parseBoolean(value);
					}
					return value;
				}
			}
		}
	}
}
