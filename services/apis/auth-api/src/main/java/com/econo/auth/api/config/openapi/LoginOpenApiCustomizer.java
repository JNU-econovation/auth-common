package com.econo.auth.api.config.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * 로그인 엔드포인트({@code POST /api/v1/auth/login})의 Swagger 문서 정의.
 *
 * <p>로그인은 컨트롤러가 아니라 Spring Security 필터(JsonLoginAuthenticationFilter)가 처리하므로 springdoc 이 자동으로 잡지
 * 못한다. 이 커스터마이저가 명세를 직접 추가한다.
 */
@Component
public class LoginOpenApiCustomizer implements OpenApiCustomizer {

	private static final String LOGIN_PATH = "/api/v1/auth/login";
	private static final String JSON = "application/json";

	@Override
	public void customise(OpenAPI openApi) {
		openApi.getPaths().addPathItem(LOGIN_PATH, new PathItem().post(loginOperation()));
	}

	private Operation loginOperation() {
		return new Operation()
				.addTagsItem("Auth")
				.summary("로그인")
				.description(
						"loginId/password 로 로그인한다. Spring Security 필터가 처리한다.\n\n"
								+ "**WEB** (`Client-Type: WEB`, 기본): at/rt HttpOnly 쿠키 설정 후 200 OK + body({ accessExpiredTime, redirectUrl }) 반환.\n"
								+ "**APP** (`Client-Type: APP`): 200 + body 로 accessToken/refreshToken/redirectUrl 반환.")
				.addParametersItem(clientTypeHeader())
				.requestBody(new RequestBody().required(true).content(jsonContent(loginRequestSchema())))
				.responses(loginResponses());
	}

	private Parameter clientTypeHeader() {
		return new Parameter()
				.in("header")
				.name("Client-Type")
				.required(false)
				.description("WEB(기본) | APP")
				.schema(new StringSchema()._enum(List.of("WEB", "APP")));
	}

	private ApiResponses loginResponses() {
		return new ApiResponses()
				.addApiResponse(
						"200",
						new ApiResponse()
								.description(
										"로그인 성공 — WEB: at/rt HttpOnly 쿠키 + body({ accessExpiredTime, redirectUrl })."
												+ " APP: body 로 accessToken/refreshToken/redirectUrl 반환.")
								.content(jsonContent(loginResponseSchema())))
				.addApiResponse("401", new ApiResponse().description("INVALID_CREDENTIALS — 자격증명 불일치"));
	}

	private Content jsonContent(Schema schema) {
		return new Content().addMediaType(JSON, new MediaType().schema(schema));
	}

	private Schema loginRequestSchema() {
		return new ObjectSchema()
				.addProperty("loginId", new StringSchema().example("hong"))
				.addProperty("password", new StringSchema().example("password123!"))
				.addProperty(
						"clientId",
						new StringSchema()
								.nullable(true)
								.description("선택 — 등록된 redirect_uri 결정에 사용. 없으면 기본 목적지로."))
				.required(List.of("loginId", "password"));
	}

	private Schema loginResponseSchema() {
		return new ObjectSchema()
				.addProperty(
						"accessToken",
						new StringSchema()
								.nullable(true)
								.description("APP에서만 반환. WEB 응답 JSON에는 키 자체가 포함되지 않음(@JsonInclude(NON_NULL))."))
				.addProperty(
						"accessExpiredTime",
						new IntegerSchema()
								.format("int64")
								.nullable(true)
								.description(
										"AT 만료 시각(epoch millis). APP에서만 반환. WEB 응답 JSON에는 키 자체가 포함되지 않음(@JsonInclude(NON_NULL))."))
				.addProperty(
						"refreshToken",
						new StringSchema()
								.nullable(true)
								.description("APP에서만 반환. WEB 응답 JSON에는 키 자체가 포함되지 않음(@JsonInclude(NON_NULL))."))
				.addProperty("redirectUrl", new StringSchema());
	}
}
