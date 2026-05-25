# ECONO Auth Common Library

[![JitPack](https://jitpack.io/v/JNU-econovation/auth-common.svg)](https://jitpack.io/#JNU-econovation/auth-common)
[![MIT License](https://img.shields.io/badge/License-MIT-green.svg)](https://choosealicense.com/licenses/mit/)

## 개요

Spring Boot 기반 마이크로서비스에서 `@PassportAuth` 어노테이션을 통해 인증/인가를 간편하게 처리할 수 있는 라이브러리입니다.

## 주요 기능

### 🎫 Passport 도메인
- 마이크로서비스 간 통신용 회원 정보 객체
- Spring Authorization Server(SAS)가 발급하는 JWT Access Token의 클레임에서 Gateway가 Passport를 구성하여 내부 헤더로 전달
- 각 서비스에서 `@PassportAuth` 어노테이션으로 자동 주입

### ⚡ @PassportAuth 어노테이션
- 컨트롤러 메서드 파라미터에 `Passport` 자동 주입
- Spring Security 스타일의 명명 규칙 채택
- 권한 체크, 만료 검증, 권한 계층, SpEL 조건 등 다양한 옵션 지원
- Base64 인코딩된 헤더 자동 디코딩

## 설치 및 설정

### 1. Gradle 의존성 추가

```kotlin
dependencies {
    implementation("com.github.JNU-econovation:auth-common:1.0.0")
}
```

### 2. 자동 설정 (권장)

**Spring Boot 2.x/3.x** 모두에서 **자동으로 설정**됩니다. 별도 설정 불필요!

```java
// 의존성만 추가하면 바로 사용 가능
@RestController
public class MyController {
    @GetMapping("/api/data")
    public String getData(@PassportAuth Passport passport) {
        return "Hello " + passport.getName();
    }
}
```

### 3. 수동 설정 (필요한 경우)

자동 설정을 비활성화한 경우에만 필요:

```java
@Configuration
@EnableWebMvc
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private PassportArgumentResolver passportArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(passportArgumentResolver);
    }
}
```

## 사용법

### 기본 사용법

```java
@RestController
public class ProgramController {

    @GetMapping("/api/programs")
    public ResponseEntity<List<Program>> getPrograms(@PassportAuth Passport passport) {
        // passport 객체를 바로 사용 가능
        Long memberId = passport.getMemberId();
        String loginId = passport.getLoginId();

        return ResponseEntity.ok(programService.getUserPrograms(memberId));
    }
}
```

### 권한 체크

```java
@RestController
public class AdminController {

    // 관리자 권한 필요
    @GetMapping("/api/admin/users")
    public ResponseEntity<List<User>> getAllUsers(
            @PassportAuth(requiredRoles = "ADMIN") Passport passport) {

        return ResponseEntity.ok(userService.getAllUsers());
    }

    // 관리자 또는 매니저 권한 필요 (OR 조건)
    @GetMapping("/api/admin/programs")
    public ResponseEntity<List<Program>> getAllPrograms(
            @PassportAuth(requiredRoles = {"ADMIN", "MANAGER"}) Passport passport) {

        return ResponseEntity.ok(programService.getAllPrograms());
    }

    // 모든 권한 필요 (AND 조건)
    @DeleteMapping("/api/admin/system/reset")
    public ResponseEntity<Void> resetSystem(
            @PassportAuth(requiredRoles = {"ADMIN", "SUPER_ADMIN"}, requireAllRoles = true)
            Passport passport) {

        systemService.reset();
        return ResponseEntity.ok().build();
    }
}
```

### 선택적 인증

```java
@RestController
public class PublicController {

    @GetMapping("/api/public/programs")
    public ResponseEntity<List<Program>> getPublicPrograms(
            @PassportAuth(required = false) Passport passport) {

        if (passport != null) {
            // 인증된 사용자용 데이터
            return ResponseEntity.ok(programService.getUserPrograms(passport.getMemberId()));
        } else {
            // 비인증 사용자용 데이터
            return ResponseEntity.ok(programService.getPublicPrograms());
        }
    }
}
```

### 비즈니스 로직에서 활용

```java
@Service
public class ProgramService {

    public List<Program> getAvailablePrograms(Passport passport) {
        List<Program> programs = programRepository.findAll();

        if (passport.isAdmin()) {
            // 관리자는 모든 프로그램 조회
            return programs;
        } else {
            // 일반 사용자는 공개 프로그램만 조회
            return programs.stream()
                .filter(Program::isPublic)
                .collect(Collectors.toList());
        }
    }

    public boolean canEditProgram(Long programId, Passport passport) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new EntityNotFoundException("Program not found"));

        // 자신이 만든 프로그램이거나 관리자인 경우 수정 가능
        return passport.isMember(program.getCreatorId()) || passport.isAdmin();
    }
}
```

### 예외 처리

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PassportException.class)
    public ResponseEntity<ErrorResponse> handlePassportException(
            PassportException e) {

        ErrorResponse error = ErrorResponse.builder()
            .code(e.getErrorCode())
            .message(e.getMessage())
            .timestamp(LocalDateTime.now())
            .build();

        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }
}
```

## Passport API

### 기본 정보 접근

```java
Long memberId = passport.getMemberId();        // 회원 ID
String loginId = passport.getLoginId();       // 로그인 아이디
String name = passport.getName();             // 이름
Integer generation = passport.getGeneration(); // 기수
String status = passport.getStatus();         // 활동 상태 (AM / RM / CM / OB)
List<String> roles = passport.getRoles();     // 권한 목록
LocalDateTime issuedAt = passport.getIssuedAt();  // 발급 시간
LocalDateTime expiresAt = passport.getExpiresAt(); // 만료 시간
```

### 권한 체크 메서드

```java
// 기본 권한 체크
boolean isAdmin = passport.isAdmin();           // 관리자 여부
boolean isManager = passport.isManager();       // 매니저 여부
boolean hasRole = passport.hasRole("USER");     // 특정 권한 보유 여부

// 복수 권한 체크
boolean hasAny = passport.hasAnyRole("ADMIN", "MANAGER");  // OR 조건
boolean hasAll = passport.hasAllRoles("USER", "ADMIN");   // AND 조건
```

### 유효성 및 권한 체크

```java
// 상태 체크 - 책임 분리된 메서드들
boolean isValid = passport.isValid();           // 구조적 유효성 (필수 데이터 존재)
boolean isExpired = passport.isExpired();       // 시간 기반 만료 검증
boolean isActive = passport.isActive();         // 종합적 사용 가능 여부 (유효 && 미만료)

// 접근 권한 체크
boolean isSelf = passport.isMember(123L);       // 특정 사용자인지 확인
boolean canAccess = passport.canAccessMember(456L);  // 자신 또는 관리자인지 확인
```

## @PassportAuth 어노테이션 옵션

### 옵션 목록

| 옵션 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `required` | boolean | true | Passport 필수 여부 |
| `validateExpiry` | boolean | true | 만료 검증 여부 |
| `requiredRoles` | String[] | {} | 필요한 권한들 |
| `requireAllRoles` | boolean | false | 모든 권한 필요 여부 |
| `includeHigherRoles` | boolean | false | 권한 계층 포함 여부 |
| `condition` | String | "" | SpEL 조건 표현식 |

### 사용 예시

```java
// 기본 사용 (인증 필수)
@PassportAuth Passport passport

// 선택적 인증
@PassportAuth(required = false) Passport passport

// 만료 검증 비활성화
@PassportAuth(validateExpiry = false) Passport passport

// 단일 권한 체크
@PassportAuth(requiredRoles = "ADMIN") Passport passport

// 다중 권한 체크 (OR 조건)
@PassportAuth(requiredRoles = {"ADMIN", "MANAGER"}) Passport passport

// 다중 권한 체크 (AND 조건)
@PassportAuth(requiredRoles = {"ADMIN", "SUPER_USER"}, requireAllRoles = true) Passport passport

// 권한 계층 지원
@PassportAuth(requiredRoles = "MANAGER", includeHigherRoles = true) Passport passport

// SpEL 조건부 권한
@PassportAuth(condition = "passport.memberId == #targetId or passport.isAdmin()") Passport passport

// 복합 조건
@PassportAuth(
    requiredRoles = "USER",
    includeHigherRoles = true,
    condition = "passport.departmentId == #deptId"
) Passport passport
```

## 에러 코드

| 에러 코드 | HTTP 상태 | 설명 |
|----------|-----------|------|
| `AUTH_UNAUTHORIZED` | 401 | 인증 실패 |
| `AUTH_FORBIDDEN` | 403 | 권한 부족 |
| `AUTH_BAD_REQUEST` | 400 | 잘못된 요청 |
| `AUTH_TOKEN_EXPIRED` | 401 | 토큰 만료 |
| `AUTH_PASSPORT_INVALID` | 401 | 유효하지 않은 Passport |

## 헤더 구조

Gateway(`BearerToPassportFilter`)에서 다음 헤더가 자동으로 설정됩니다:

```http
X-User-Passport: eyJ1c2VySWQiOjEsInN0dWRlbn...
```

- `X-User-Passport`: Base64 인코딩된 완전한 Passport JSON

Gateway는 클라이언트로부터 `Authorization: Bearer <SAS-JWT>` 헤더를 수신하고, SAS JWKS(RS256)로 서명을 검증한 뒤 JWT 클레임에서 Passport를 구성하여 내부 서비스로 전달합니다. SAS OAuth 엔드포인트(`/oauth2/**`, `/.well-known/**`, `/userinfo`)와 인증 경로(`/api/v1/auth/**`)는 Bearer 토큰 검증 없이 통과됩니다.

## 개발 가이드

### 테스트 작성

```java
@Test
void testPassportInjection() {
    // given
    Passport mockPassport = new Passport(
        123L, "testuser", "테스터",
        1, "AM",
        List.of("USER"), LocalDateTime.now(),
        LocalDateTime.now().plusHours(1)
    );

    String encodedPassport = Base64.getEncoder().encodeToString(
        objectMapper.writeValueAsString(mockPassport).getBytes()
    );

    // when & then
    mockMvc.perform(get("/api/programs")
            .header("X-User-Passport", encodedPassport))
        .andExpected(status().isOk());
}
```

### 커스텀 권한 체크

```java
public class CustomPermissionChecker {

    public static boolean canManageProgram(Passport passport, Program program) {
        // 프로그램 생성자이거나 관리자인 경우
        return passport.isMember(program.getCreatorId()) || passport.isAdmin();
    }

    public static boolean canViewSensitiveData(Passport passport) {
        // ADMIN 또는 MANAGER 권한 필요
        return passport.hasAnyRole("ADMIN", "MANAGER");
    }
}
```

## 개발 환경

- **Java**: 21
- **Spring Boot**: 3.2.2
- **Gradle**: Kotlin DSL
- **Build Tools**: Spotless (코드 포맷팅)

## 빌드 및 테스트

```bash
# 빌드
./gradlew build

# 테스트 실행
./gradlew test

# 코드 포맷팅
./gradlew format

# 코드 포맷팅 체크
./gradlew spotlessCheck
```

## 버전 히스토리

- **1.0.0**: 초기 릴리즈
  - @PassportAuth 어노테이션 지원
  - Passport 도메인 객체
  - Spring Boot 3.x 자동 설정
  - 권한 체계 및 유효성 검증
