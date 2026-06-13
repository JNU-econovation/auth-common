# Convention

auth-common 프로젝트의 코드 컨벤션.

## 목차

- [1. 네이밍 규칙](#1-네이밍-규칙)
  - [1.1 패키지](#11-패키지)
  - [1.2 클래스](#12-클래스)
  - [1.3 메서드](#13-메서드)
  - [1.4 상수](#14-상수)
  - [1.5 동적 역할](#15-동적-역할)
- [2. 코드 스타일](#2-코드-스타일)
  - [2.1 포맷팅](#21-포맷팅)
  - [2.2 Lombok 사용](#22-lombok-사용)
  - [2.3 불변성](#23-불변성)
  - [2.4 JSON 직렬화](#24-json-직렬화)
  - [2.5 Validation](#25-validation)
- [3. 예외 처리](#3-예외-처리)
  - [3.1 정적 팩토리 메서드 패턴](#31-정적-팩토리-메서드-패턴)
  - [3.2 에러 전파](#32-에러-전파)
- [4. Javadoc](#4-javadoc)
  - [4.1 규칙](#41-규칙)
  - [4.2 private 메서드](#42-private-메서드)
- [5. 테스트 컨벤션](#5-테스트-컨벤션)
  - [5.1 구조](#51-구조)
  - [5.2 테스트 작성 패턴](#52-테스트-작성-패턴)
  - [5.3 테스트 유형별 도구](#53-테스트-유형별-도구)
- [6. 빌드](#6-빌드)
- [7. 의존성 선언](#7-의존성-선언)
- [8. API 문서화 (Swagger/OpenAPI)](#8-api-문서화-swaggeropenapi)
  - [8.1 문서 어노테이션 분리 (docs 인터페이스)](#81-문서-어노테이션-분리-docs-인터페이스)
  - [8.2 전역 설정 위치](#82-전역-설정-위치)
  - [8.3 태그(@Tag)](#83-태그tag)
  - [8.4 보안 스킴](#84-보안-스킴)
  - [8.5 Gateway 주입 파라미터 숨김](#85-gateway-주입-파라미터-숨김)
  - [8.6 필터 처리 엔드포인트](#86-필터-처리-엔드포인트)
  - [8.7 DTO 스키마 문서화(@Schema)](#87-dto-스키마-문서화schema)
  - [8.8 비공개 엔드포인트](#88-비공개-엔드포인트)

---

## 1. 네이밍 규칙

### 1.1 패키지

- 역도메인 표기법: `com.econo.common.auth` (econo-passport — 외부 의존성), `com.econo.auth` (member, common-infra, service-client, auth-api, api-gateway)
- **3계층 구조 (libs·apps 공통)**: 계층 → 역할 2단 구성.
  - `presentation/controller` — HTTP 컨트롤러
  - `presentation/dto` — 요청·응답 DTO
  - `presentation/util` — 컨트롤러 보조 유틸 (예: `TokenCookieManager`)
  - `application/usecase` — 입력 포트 인터페이스 (`{Action}UseCase`)
  - `application/service` — 유스케이스 구현체
  - `application/repository` — 출력 포트 인터페이스 (도메인 객체만 사용)
  - `application/domain` — 도메인 객체 (순수 Java, 프레임워크 의존 없음)
  - `persistence/entity` — JPA `@Entity` 클래스
  - `persistence/repository` — Spring Data JPA 인터페이스 + 출력 포트 구현 어댑터 (entity↔domain 변환 담당)
  - `config/` — 일반 설정·빈 와이어링
  - `config/security/` — Spring Security에 결합된 클래스 전용 (SecurityConfig, UserDetailsService, AuthenticationFilter, GlobalFilter, JWT 처리 유틸). **`presentation/filter` 패키지는 사용하지 않는다.**
  - `exception/` — 도메인 예외
- **auth-common-lib 전용**: `core/passport/` (도메인), `web/annotation/`, `web/resolver/`, `config/` (Auto-Configuration)
- 소문자만 사용, 단어 구분 없이 연결
- **의존성 방향 규칙**: presentation·config/security는 `application.usecase` 인터페이스에만 의존한다. `application.service` 구현체나 `application.repository`·`persistence`를 직접 참조하지 않는다. 일반 `config/`(보안 아님) 와이어링 클래스는 `application.repository`와 `application.service`를 참조해도 된다(빈 등록·CORS 등 설정 책임).

### 1.2 클래스

- PascalCase 사용
- 역할을 접미사로 표현

| 유형 | 패턴 | 예시 |
|------|------|------|
| 도메인 객체 | `{Name}` | `Passport`, `Member` |
| 예외 | `{Domain}Exception` | `PassportException`, `InvalidCredentialsException` |
| 어노테이션 | `{Name}` | `PassportAuth` |
| Resolver | `{Name}ArgumentResolver` | `PassportArgumentResolver` |
| 설정 | `{Domain}AutoConfiguration` | `AuthAutoConfiguration` |
| 설정 (일반) | `{Domain}Config` | `ApplicationServiceConfig` |
| 상수 클래스 | `{Name}s` (복수형) | `Roles` |
| 입력 포트 (UseCase 인터페이스) | `{Action}UseCase` | `SignupUseCase`, `MemberQueryUseCase`, `LoginTokenUseCase` |
| 출력 포트 (repository 인터페이스) | `{Resource}Repository` 또는 서술적 이름 | `MemberRepository`, `PasswordHasher`, `SasClientRegistrar` |
| 유스케이스·서비스 구현체 | `{Action}Service` 또는 `{Action}Resolver` | `SignupService`, `LoginRedirectResolver` |
| JPA 엔티티 | `{Name}JpaEntity` | `MemberJpaEntity`, `ServiceClientJpaEntity` |
| Spring Data JPA 인터페이스 | `{Name}JpaRepository` | `MemberJpaRepository` |
| 출력 포트 구현 어댑터 | `{Name}RepositoryAdapter` 또는 `{Algo}{Role}Adapter` | `MemberRepositoryAdapter`, `BCryptPasswordHasherAdapter` |
| 테스트 | `{TargetClass}Test` | `PassportTest`, `SignupServiceTest` |

### 1.3 메서드

- camelCase 사용
- 의미 있는 접두사 사용

| 접두사 | 용도 | 예시 |
|--------|------|------|
| `is` | boolean 상태 확인 | `isValid()`, `isExpired()`, `isActive()` |
| `has` | 보유 여부 확인 | `hasRole()`, `hasAnyRole()`, `hasAllRoles()` |
| `can` | 권한/능력 확인 | `canAccessMember()` |
| `get` | 값 반환 (Lombok) | `getMemberId()` |
| `create` | 객체 생성 | `createEvaluationContext()` |
| `validate` | 검증 수행 | `validateRoles()`, `validateCondition()` |
| `handle` | 예외/분기 처리 | `handleMissingPassport()` |

### 1.4 상수

- UPPER_SNAKE_CASE 사용
- 의미 단위로 그룹화하고 주석 구분선 사용

```java
// ==================== 기본 권한 ====================
public static final String USER = "USER";
public static final String MANAGER = "MANAGER";
public static final String ADMIN = "ADMIN";
public static final String SUPER_ADMIN = "SUPER_ADMIN";
```

### 1.5 동적 역할

- `{CATEGORY}_{IDENTIFIER}_{ROLE}` 패턴
- 예: `DEPARTMENT_CS_ADMIN`, `PROJECT_2024_MEMBER`, `EVENT_GRADUATION_STAFF`

## 2. 코드 스타일

### 2.1 포맷팅

Spotless + Google Java Format 1.17.0을 사용한다. 수동 포맷팅 불필요.

```bash
# 포맷팅 적용
./gradlew format

# 포맷팅 검사
./gradlew spotlessCheck
```

- 들여쓰기: 탭 (2칸 너비)
- 줄 끝: 개행 문자로 종료
- import: 미사용 import 자동 제거
- 후행 공백: 자동 제거

### 2.2 Lombok 사용

- `@Getter`: 필드 접근자 생성
- `@RequiredArgsConstructor`: final 필드 기반 생성자
- `@Slf4j`: Logger 생성
- `@Getter`를 클래스 레벨에 선언하여 모든 필드에 적용
- **생성자는 `@RequiredArgsConstructor`를 우선 사용**하고, 사용할 수 없는 경우에만 직접 생성자를 작성한다
  - 직접 생성자가 필요한 경우: `@JsonCreator`로 역직렬화 제어, 방어적 복사 등 생성자 내부 로직이 필요한 경우

```java
// 기본: @RequiredArgsConstructor 사용
@Slf4j
@RequiredArgsConstructor
public class PassportArgumentResolver { ... }

// 예외: 생성자 내부 로직이 필요한 경우 직접 작성
@Getter
public class Passport {
  @JsonCreator
  public Passport(
      @JsonProperty("memberId") Long memberId,
      @JsonProperty("roles") List<String> roles, ...) {
    this.memberId = memberId;
    this.roles = roles != null ? List.copyOf(roles) : List.of();  // 방어적 복사
  }
}
```

### 2.3 불변성

- 도메인 객체의 필드는 `private final`로 선언
- 컬렉션은 `List.copyOf()`로 방어적 복사
- null 컬렉션은 `List.of()`로 빈 리스트 변환

```java
this.roles = roles != null ? List.copyOf(roles) : List.of();
```

### 2.4 JSON 직렬화

- `@JsonCreator` + `@JsonProperty`로 역직렬화 생성자 명시
- 계산 필드는 `@JsonIgnore`로 직렬화에서 제외
- `LocalDateTime`은 `jackson-datatype-jsr310`으로 처리

```java
@JsonCreator
public Passport(
    @JsonProperty("memberId") Long memberId,
    @JsonProperty("loginId") String loginId,
    ...
) { ... }

@JsonIgnore
public boolean isExpired() { ... }
```

### 2.5 Validation

- 필수 필드에 `@NotNull` 선언 (Jakarta Bean Validation)

```java
@NotNull private final Long memberId;
@NotNull private final List<String> roles;
```

## 3. 예외 처리

### 3.1 정적 팩토리 메서드 패턴

예외는 생성자 직접 호출 대신 정적 팩토리 메서드를 사용한다.

```java
// O
throw PassportException.unauthorized("Authentication required");
throw PassportException.forbidden("Insufficient permissions");
throw PassportException.expired(passport.getMemberId());

// X
throw new PassportException(HttpStatus.UNAUTHORIZED, "...");
```

### 3.2 에러 전파

- `PassportException`은 catch 후 재throw
- 그 외 예외는 `PassportException.badRequest()`로 래핑
- 로깅은 `log.error()` 또는 `log.warn()`으로 수행

```java
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
```

## 4. Javadoc

### 4.1 규칙

- 모든 `public` 클래스와 메서드에 Javadoc 작성
- 클래스에는 `<h2>` 태그로 사용법 섹션 구분
- `@param`, `@return` 태그 필수
- `@see`로 관련 메서드 교차 참조
- 코드 예시는 `<pre>{@code ...}</pre>` 블록 사용

```java
/**
 * 시간 기반 만료 검증 - 현재 시간 기준으로 만료되었는지 확인
 *
 * <p>구조적 유효성과는 무관하게 시간적으로만 만료 여부를 판단합니다.
 *
 * @return 현재 시간이 expiresAt을 초과했으면 true, 그렇지 않으면 false
 * @see #isValid() 구조적 유효성 검증
 * @see #isActive() 종합적 사용 가능 여부 검증
 */
```

### 4.2 private 메서드

- Javadoc 대신 `/** 한 줄 설명 */` 형태의 간단한 주석 사용

```java
/** 권한 검증 (권한 계층 지원 포함) */
private void validateRoles(Passport passport, PassportAuth annotation) { ... }
```

## 5. 테스트 컨벤션

### 5.1 구조

- 테스트 클래스 위치는 src 구조를 미러링
- `@Nested`로 테스트 그룹화
- `@DisplayName`으로 한글 테스트명 작성

```java
class PassportTest {

    @Nested
    @DisplayName("Passport 생성 테스트")
    class CreationTest {

        @Test
        @DisplayName("정상적인 Passport 생성")
        void createValidPassport() { ... }
    }
}
```

### 5.2 테스트 작성 패턴

- Given-When-Then 패턴 사용 (주석으로 구분)
- AssertJ fluent assertion 사용
- 메서드명은 영문 camelCase, `@DisplayName`은 한글

```java
@Test
@DisplayName("null roles로 생성 시 빈 리스트")
void createWithNullRoles() {
    // given
    LocalDateTime now = LocalDateTime.now();

    // when
    Passport passport = new Passport(123L, "testuser", "테스터", 1, "AM", null, now, now.plusHours(1));

    // then
    assertThat(passport.getRoles()).isEmpty();
}
```

### 5.3 테스트 유형별 도구

| 유형 | 어노테이션/도구 | 용도 |
|------|----------------|------|
| 단위 테스트 | `@Test`, `@Nested` | 도메인 로직 검증 |
| Mock 테스트 | `@ExtendWith(MockitoExtension.class)` | 의존성 격리 |
| 통합 테스트 | `@SpringBootTest` + `MockMvc` | E2E 흐름 검증 |
| JPA 통합 테스트 | `@DataJpaTest` + Testcontainers PostgreSQL | JPA Repository 슬라이스 검증 |

## 6. 빌드

```bash
# 전체 빌드 (포맷 검사 + 테스트)
./gradlew check

# 테스트만 실행
./gradlew test

# 포맷팅 적용
./gradlew format

# JAR 생성
./gradlew jar
```

## 7. 의존성 선언

- 라이브러리 사용자에게 전이되어야 하는 의존성은 `api`
- 컴파일 시에만 필요한 의존성은 `compileOnly`
- 테스트 전용은 `testImplementation`

```kotlin
api("org.springframework.boot:spring-boot-starter-web")         // 전이 의존성
compileOnly("org.projectlombok:lombok")                         // 컴파일 전용
testImplementation("org.springframework.boot:spring-boot-starter-test") // 테스트 전용
```

## 8. API 문서화 (Swagger/OpenAPI)

springdoc-openapi 기반 API 문서 작성 규칙. 컨트롤러는 로직만, 문서는 분리한다.

### 8.1 문서 어노테이션 분리 (docs 인터페이스)

- Swagger 어노테이션(`@Operation`, `@ApiResponses`, `@Tag`, `@SecurityRequirement` 등)은 **컨트롤러에 직접 달지 않는다.** `presentation/docs/` 패키지의 전용 인터페이스에 정의한다.
- 컨트롤러는 그 인터페이스를 `implements`하고 각 핸들러에 `@Override`만 단다. 컨트롤러에는 Spring 웹 어노테이션(`@PostMapping` 등)과 비즈니스 로직만 남긴다.
- 인터페이스명 규칙: **`{Prefix}Controller` ↔ `{Prefix}ApiDocs`** (예: `ClientController` → `ClientApiDocs`)
- 인터페이스 메서드 시그니처는 컨트롤러 메서드와 **동일하게** 유지한다. (인터페이스에서 참조해야 하는 DTO는 `public`으로 선언)

```java
// presentation/docs/ClientApiDocs.java
@Tag(name = "Client")
public interface ClientApiDocs {
  @Operation(summary = "OAuth 클라이언트 셀프 등록", security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponses({ @ApiResponse(responseCode = "201", description = "등록 성공") })
  ResponseEntity<?> registerClient(Passport passport, SelfRegisterClientRequest request);
}

// presentation/controller/ClientController.java
@RestController
public class ClientController implements ClientApiDocs {
  @Override
  @PostMapping
  public ResponseEntity<?> registerClient(@PassportAuth Passport passport, @Valid @RequestBody SelfRegisterClientRequest request) { ... }
}
```

### 8.2 전역 설정 위치

- OpenAPI 전역 정의와 커스터마이저는 `config/openapi/` 패키지에 둔다 (`OpenApiConfig`, `*OpenApiCustomizer`).

### 8.3 태그(@Tag)

- `name`은 **도메인 한 단어만** 사용한다. 하이픈·부가설명을 넣지 않는다. (예: `@Tag(name = "Admin")`, `@Tag(name = "Client")`)
- 태그의 **설명·노출 순서**는 전역 `OpenApiConfig`의 `@OpenAPIDefinition(tags = {...})`에서 단일 관리한다.

### 8.4 보안 스킴

- 인증은 **쿠키 기반**으로 표기한다 — `cookieAuth` (apiKey, in: cookie, name: `at`). `OpenApiConfig`의 `@SecurityScheme`로 선언한다.
- 인증이 필요한 엔드포인트는 `@SecurityRequirement(name = "cookieAuth")`를 단다. 공개 엔드포인트(가입 등)에는 달지 않는다.

### 8.5 Gateway 주입 파라미터 숨김

- `Passport`처럼 Gateway가 `X-User-Passport`로 주입하는 값은 클라이언트 입력이 아니므로 명세에 노출하지 않는다. `OpenApiConfig`에서 전역 1회 등록한다.

```java
@PostConstruct
void ignorePassportParameter() {
  SpringDocUtils.getConfig().addRequestWrapperToIgnore(Passport.class);
}
```

### 8.6 필터 처리 엔드포인트

- 컨트롤러가 아니라 Spring Security 필터가 처리하는 엔드포인트(로그인 등)는 springdoc이 자동 인식하지 못한다. `OpenApiCustomizer` 구현체(`config/openapi/`)로 명세를 직접 추가한다.

### 8.7 DTO 스키마 문서화(@Schema)

- 응답은 `Map<String, Object>` 대신 **DTO(record)**로 정의해 OpenAPI 스키마가 노출되게 한다.
- `presentation/dto/` 패키지의 **요청·응답 DTO 모두** 각 필드에 `@Schema(description = "...", example = "...")`를 작성한다. nullable 필드는 `nullable = true`를 추가한다. 클래스 레벨 `@Schema`는 선택이다.

### 8.8 비공개 엔드포인트

- 내부 전용(게이트웨이·CLI 전용) 엔드포인트는 `@Hidden`으로 문서에서 제외한다. (예: JWKS, Internal API Key 부트스트랩)
