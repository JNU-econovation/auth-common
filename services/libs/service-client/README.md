# service-client

OAuth 클라이언트 등록·redirectUri 관리·Gateway 라우팅 도메인 라이브러리.
ServiceClient·ServiceRoute 도메인, 헥사고날 구조(포트/어댑터), Spring Boot AutoConfiguration을 포함한다.

---

## Quick Reference

| 항목 | 값 |
|------|-----|
| 패키지 루트 | `com.econo.auth.client` |
| Gradle 의존 경로 | `implementation(project(":services:libs:service-client"))` |
| 주요 연관 모듈 | `common-infra` (JpaAuditing 공유), `auth-api` (소비자) |
| AutoConfiguration | `com.econo.auth.client.config.ServiceClientAutoConfiguration` |
| API 엔드포인트 | 해당 없음 — libs 모듈. 소비자: `AdminClientController` (`/api/v1/clients`, `/api/v1/routes`) |

---

## 비즈니스 규칙

- `grantType`이 null이면 서비스 계층에서 `CLIENT_CREDENTIALS`로 디폴트 처리한다. 컨트롤러는 null을 그대로 전달한다.
- `authorization_code` 클라이언트는 `clientSecret`을 발급하지 않는다. Basic Auth가 필요한 redirectUri 관리 endpoint에 접근할 수 없다.
- `clientSecret`(BCrypt 해시)은 SAS `RegisteredClient`에 저장되며 `service_client` 테이블에는 저장되지 않는다. 원본 secret은 등록 응답에서 1회만 반환한다.
- `apiKeyHash` 필드는 항상 null이다. 향후 API Key 채널 도입 시 부활 예정.
- `GrantType.fromString`은 null 입력 시 null을 반환한다. 알 수 없는 비-null 값이면 `IllegalArgumentException`을 throw하며, `AdminClientController`가 catch하여 `UnsupportedGrantTypeException`으로 변환한다.
- ⚠️ `ServiceClientAutoConfiguration`이 `@EnableJpaRepositories` / `@EntityScan`으로 `com.econo.auth.client.adapter.out.persistence`를 직접 스캔한다. 다른 AutoConfiguration에서 이 패키지를 중복 선언하면 충돌이 발생한다.
- `SasRedirectUriManager` 포트를 통해 SAS `RegisteredClientRepository` 직접 의존을 `SasRedirectUriManagerAdapter`로 격리한다. application 계층은 SAS 클래스를 직접 import하지 않는다.
- `ClientRedirectUriService.extractAllowedOrigins`는 모든 등록 클라이언트의 redirectUri에서 scheme+host+port 기준으로 CORS 허용 오리진을 추출한다. Gateway의 `DynamicCorsConfigurationSource`가 이 메서드를 호출한다.

---

## 코드 진입점

| 구분 | 경로 |
|------|------|
| 도메인 | `services/libs/service-client/src/main/java/com/econo/auth/client/domain/` |
| 아웃바운드 포트 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/port/out/` |
| 유스케이스 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/` |
| JPA 어댑터 | `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/` |
| SAS 어댑터 | `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/sas/` |
| AutoConfiguration | `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java` |
| 예외 | `services/libs/service-client/src/main/java/com/econo/auth/client/exception/` |

---

## 에러 코드

> 에러 정의: `services/libs/service-client/src/main/java/com/econo/auth/client/exception/`

| 예외 클래스 | HTTP 매핑 | 에러 코드 | 발생 조건 |
|------------|-----------|-----------|-----------|
| `InvalidClientException` | 404 | CLIENT_NOT_FOUND | clientId 미존재 |
| `RedirectUriRequiredException` | 400 | REDIRECT_URI_REQUIRED | redirectUri 누락·한도 초과·유효하지 않은 URI |
| `UnsupportedGrantTypeException` | 400 | UNSUPPORTED_GRANT_TYPE | 지원하지 않는 grantType |
| `DuplicateClientNameException` | 409 | DUPLICATE_CLIENT_NAME | clientName 중복 |

> 표의 HTTP 매핑은 `GlobalExceptionHandler`(`services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`)가 명시적 `ResponseEntity`로 반환하는 실제 상태 코드다.
> 예외 클래스의 `@ResponseStatus` 어노테이션은 표 매핑과 일치하나 핸들러가 우선하므로 표만 보면 된다. (`InvalidClientException`은 `@ResponseStatus` 없음 — GlobalExceptionHandler가 404로 처리)

---

## 관련 모듈

| 모듈 | Gradle path | 관계 |
|------|-------------|------|
| common-infra | `:services:libs:common-infra` | JpaAuditingConfig 공유 (`@EnableJpaAuditing` AutoConfiguration 선언 위치) |
| auth-api | `:services:apis:auth-api` | 소비자 — `AdminClientController`, `GlobalExceptionHandler`, `DynamicCorsConfigurationSource` |
