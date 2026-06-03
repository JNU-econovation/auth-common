---
name: register-service
description: |
  새 Spring Boot 서비스를 api-gateway + auth-api 에코시스템에 연결한다.
  auth-api 클라이언트 등록 → Gateway 라우팅 추가 → 서비스에 econo-passport 연동 → 동작 확인까지 한 번에 처리.

  다음 상황에서 반드시 이 스킬을 사용한다:
  - "새 서비스 Gateway에 연결해줘", "새 서비스 auth 연동"
  - "서비스 등록해줘", "Gateway 뒤에 붙여줘"
  - "/register-service" 직접 호출
  - 새 Spring Boot 서비스가 추가되고 인증이 필요할 때

  ARGUMENTS: 서비스명, 서비스 경로(선택), 업스트림 URL(선택)
  예: "EEOS-BE /Users/mando/study/eeos/EEOS-BE/eeos"
---

# register-service

새 서비스를 Gateway 에코시스템에 연결한다. 완료 후 해당 서비스의 모든 API가
`at 쿠키 / Bearer AT`만으로 인증되고, `@PassportAuth Passport passport`로 사용자 정보를 받을 수 있다.

---

## 전제 조건 파악

시작 전 다음을 확인한다:

1. **auth-api 주소** — `AUTH_API_ADMIN_URL` 환경변수 또는 사용자에게 물어본다 (기본: `http://localhost:8081`)
2. **서비스 업스트림 URL** — 서비스가 실행되는 주소 (예: `http://new-service:8080`)
3. **서비스 경로 접두사** — Gateway에서 이 서비스로 라우팅할 경로 (예: `/api/new-service`)
4. **서비스 프로젝트 경로** — econo-passport를 추가할 서비스의 소스 경로

---

## Step 1. auth-api에 클라이언트 등록

```bash
curl -X POST ${AUTH_API_URL:-http://localhost:8081}/api/v1/admin/clients \
  -H "Content-Type: application/json" \
  -d '{
    "grantType": "client_credentials",
    "clientName": "<서비스명>",
    "upstreamUrl": "<업스트림 URL>",
    "pathPrefix": "<경로 접두사>"
  }'
```

응답에서 `clientId`와 `clientSecret`(1회 노출) 기록:
```json
{
  "clientId": "...",
  "clientSecret": "...",
  "routeId": "..."
}
```

> `pathPrefix`가 이미 등록된 경우 409 → 다른 경로 사용

---

## Step 2. Gateway 라우팅 추가

`services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java`를 수정한다:

```java
// 기존 eeos 라우트 아래에 추가
.route(
    "<서비스명-소문자>",
    r -> r.path("<pathPrefix>/**")
        .filters(f -> f.removeRequestHeader("Authorization"))
        .uri("<업스트림 URL>"))
```

그리고 `permittedPaths()`에 이 서비스의 공개 경로가 있으면 추가한다:
```java
"<pathPrefix>/health-check",  // 예시
```

---

## Step 3. 서비스에 econo-passport 연동

서비스 프로젝트 경로가 주어진 경우 `/use-passport` 스킬을 호출한다:

```
/use-passport <서비스명> <프로젝트 경로>
```

또는 수동으로:

### build.gradle.kts

```kotlin
repositories { maven("https://jitpack.io") }
dependencies {
    implementation("com.github.JNU-econovation:econo-passport:1.0.3")
}
```

### PassportAuthenticationFilter 등록

`SecurityFilterChainConfig`(또는 동등한 설정)의 authenticated 체인에 추가:

```java
// @Component 없이 Security 체인에만 등록
httpSecurity.addFilterBefore(new PassportAuthenticationFilter(), LogoutFilter.class);
```

> `PassportAuthenticationFilter`는 `@Component`로 등록하면 안 됨.
> Servlet 필터로 자동 등록 시 SecurityContextHolderFilter 리셋으로 인증 무효화.

### MemberArgumentResolver (기존 @Member 방식 서비스)

기존 `@Member Long memberId` 방식이 있으면 SecurityContext 우선 읽도록 수정:

```java
@Override
public Object resolveArgument(...) {
    // Passport 필터가 설정한 JwtAuthentication 우선 사용
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthentication jwtAuth) {
        return jwtAuth.getPrincipal();
    }
    // fallback: 기존 토큰 파싱
    ...
}
```

---

## Step 4. 동작 확인

### Gateway 재기동

```bash
pkill -f "ApiGatewayApplication" 2>/dev/null
cd <auth-common 경로>
AUTH_API_URI=... EEOS_API_URI=... <새 서비스 환경변수> \
  ./gradlew :services:apis:api-gateway:bootRun --args='--server.port=8082' &
```

### 연결 테스트

```bash
# 1. 로그인 → at 쿠키 획득
curl -c /tmp/test-cookies.txt -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"loginId": "<테스트 계정>", "password": "<비밀번호>"}'

# 2. 새 서비스 API 호출 (쿠키만으로)
curl -b /tmp/test-cookies.txt \
  "http://localhost:8082<pathPrefix>/health-check"
# → 200이면 성공, 401이면 Step 3 재확인

# 3. 인증 필요한 API 호출
curl -b /tmp/test-cookies.txt \
  "http://localhost:8082<pathPrefix>/api/some-endpoint"
# → 200: 완료 ✅  401: 인증 실패  404: 인증은 됐지만 데이터 없음 (정상)
```

---

## 완료 체크리스트

- [ ] auth-api에 client_credentials 클라이언트 등록 (`clientId`, `clientSecret` 저장 — clientSecret은 등록 응답에서 1회만 노출 — 반드시 즉시 저장)
- [ ] `GatewayRoutingConfig`에 라우트 추가 + Gateway 재기동
- [ ] 서비스에 econo-passport 의존성 추가
- [ ] `PassportAuthenticationFilter` Security 체인에 등록
- [ ] `at` 쿠키로 Gateway 경유 API 호출 → 200 확인
- [ ] 쿠키 없이 요청 → 401 확인

---

## 트러블슈팅

**401이 계속 난다면:**
1. Gateway 로그 확인: "JWT verification failed" → JWKS 접근 문제
2. "Bearer token missing" → at 쿠키가 요청에 없음
3. `at` 쿠키 도메인 확인: 로컬에선 `COOKIE_SECURE=false` 필요

**Passport가 서비스까지 안 온다면:**
`PassportAuthenticationFilter`가 `@Component`로 등록됐는지 확인 → 제거하고 Security 체인에만 등록
