# 새 서비스를 인증 시스템에 연동하면 얻는 것

새 백엔드 서비스를 econovation 인증 인프라에 올렸을 때 **개발자 입장에서 실제로 무엇이 가능해지는지** 정리한다.

---

## 연동 결과 요약

| 전 | 후 |
|---|---|
| JWT 파싱 코드 직접 작성 | `@PassportAuth Passport passport` 한 줄 |
| 공개키 관리, 서명 검증 직접 구현 | Gateway가 대신 처리, 서비스는 신경 안 써도 됨 |
| 로그인 API 직접 구현 | auth-api가 발급, 서비스는 Passport만 수신 |
| 타 서비스 인증 토큰 별도 관리 | SSO — 사용자가 어디서 로그인해도 동일한 Passport |

---

## Step 1: 의존성 추가 (build.gradle.kts)

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.JNU-econovation:econo-passport:1.0.3")
}
```

---

## Step 2: Security 설정 (Spring MVC 기준)

```java
@Configuration
@EnableWebMvc
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PassportArgumentResolver(new ObjectMapper()));
    }
}
```

Spring Boot `@SpringBootApplication`이 있으면 자동 설정 적용:

```yaml
# application.yml
spring:
  autoconfigure:
    exclude: []  # AuthAutoConfiguration 자동 등록됨
```

---

## Step 3: 컨트롤러에서 사용

### 기본 — 로그인한 사람 정보

```java
@GetMapping("/my-profile")
public ResponseEntity<ProfileResponse> getMyProfile(
        @PassportAuth Passport passport) {

    return ResponseEntity.ok(ProfileResponse.of(
        passport.getMemberId(),
        passport.getName(),
        passport.getGeneration()
    ));
}
```

### 권한 검증

```java
// ADMIN만 접근 가능
@GetMapping("/admin-only")
public ResponseEntity<?> adminPage(
        @PassportAuth(requiredRoles = "ADMIN") Passport passport) { ... }

// ADMIN 또는 MANAGER
@GetMapping("/manage")
public ResponseEntity<?> managePage(
        @PassportAuth(requiredRoles = {"ADMIN", "MANAGER"}) Passport passport) { ... }

// 선택적 인증 (비로그인도 허용)
@GetMapping("/public")
public ResponseEntity<?> publicPage(
        @PassportAuth(required = false) Passport passport) {
    boolean loggedIn = passport != null;
    ...
}
```

### 본인 리소스 접근 제어

```java
@GetMapping("/posts/{postId}")
public ResponseEntity<?> getPost(
        @PassportAuth Passport passport,
        @PathVariable Long postId) {

    Post post = postService.findById(postId);

    // 본인 또는 ADMIN만 수정 가능
    if (!passport.canAccessMember(post.getAuthorId())) {
        return ResponseEntity.status(403).build();
    }
    ...
}
```

### SpEL 조건

```java
// path variable의 memberId와 요청자 일치 확인
@GetMapping("/members/{memberId}/settings")
public ResponseEntity<?> getSettings(
        @PassportAuth(condition = "#memberId == memberId") Passport passport,
        @PathVariable Long memberId) { ... }
```

---

## Step 4: Gateway 라우트 등록

새 서비스를 Gateway에 등록해야 외부에서 접근 가능하다. 두 가지 방법:

### 방법 A: admin API로 등록 (운영 중 추가)

```bash
curl -X POST https://gateway.econovation.kr/api/v1/admin/clients \
  -H "Content-Type: application/json" \
  -d '{
    "grantType": "client_credentials",
    "clientName": "신규 서비스",
    "upstreamUrl": "http://new-service:8080",
    "pathPrefix": "/api/new"
  }'
```

### 방법 B: GatewayRoutingConfig.java 수정 (코드 변경)

```java
// api-gateway/GatewayRoutingConfig.java
.route("new-service", r ->
    r.path("/api/new/**")
     .filters(f -> f.removeRequestHeader("Authorization"))
     .uri(newServiceUri))
```

> 자세한 절차는 `.claude/skills/register-service/SKILL.md` 참고

---

## 얻게 되는 것 — 체크리스트

- [x] `@PassportAuth`로 로그인한 사람의 **memberId, 이름, 기수, 상태, 권한** 바로 사용
- [x] `passport.isAdmin()`, `passport.hasRole("MANAGER")` 등 **권한 체크** 한 줄
- [x] `POST /api/v1/members/batch`로 **다른 회원 정보** 조회 가능
- [x] Gateway가 JWT 검증을 대신 처리 → **서비스에 JWT 라이브러리 불필요**
- [x] EEOS, Makers 등 다른 서비스와 **SSO 자동 공유**
- [x] auth-api 장애 시에도 **이미 발급된 AT는 계속 동작** (Gateway가 JWKS 캐시로 검증)
