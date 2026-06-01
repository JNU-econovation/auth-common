# ADR-0010: Gateway 인증 제외 경로를 yml로 외부화

- **상태:** Accepted
- **결정일:** 2026-06-01
- **결정자:** econovation 개발팀

---

## 배경

`GatewayRoutingConfig.permittedPaths()`가 자바 코드에 하드코딩되어 있었다.

```java
// 변경 전 — 경로 추가 시 코드 수정 + 재컴파일 + 재배포 필요
public List<String> permittedPaths() {
    return List.of("/api/v1/auth/signup", "/oauth2/", ...);
}
```

새 공개 경로(예: `/api/v1/members/batch`) 추가 시 소스 코드 변경 없이 설정만 수정하고 싶었다.

또한 `BearerToPassportFilter.isProtectedPath()`가 `startsWith`를 사용해 `/oauth2/` 패턴이 `/oauth2-bypass` 같은 경로도 통과시키는 오탐 가능성이 있었다.

---

## 결정

**`@ConfigurationProperties(prefix = "gateway")`를 도입하여 `application.yml`에서 경로 목록을 로드한다. 경로 매칭은 Spring `PathPatternParser`(Ant 패턴)를 사용한다.**

```yaml
# application.yml
gateway:
  permitted-paths:
    - /api/v1/auth/signup
    - /oauth2/**       # ← /oauth2/authorize, /oauth2/token 등 매칭
    - /.well-known/**
    - /actuator/**
```

```java
@Configuration
@ConfigurationProperties(prefix = "gateway")
public class GatewayRoutingConfig {
    private List<String> permittedPaths = List.of();
    ...
}
```

---

## 근거

1. **설정과 코드 분리**: 새 공개 경로 추가 시 `application.yml`만 수정하면 됨 (환경별 오버라이드도 가능)
2. **오탐 방지**: `startsWith("/oauth2/")` → `PathPattern.matches("/oauth2/**")` — `/oauth2-bypass` 같은 경로는 매칭 안 됨
3. **운영 유연성**: 운영 환경에서 환경변수(`SPRING_APPLICATION_JSON` 등)로 경로 추가 가능

---

## 결과

- `permittedPaths()`에서 기존 `List.of(...)` 제거, yml 기반 주입으로 교체
- `BearerToPassportFilter`에서 `PathPatternParser` 사용
- 테스트에서 mock 설정 패턴도 `/oauth2/` → `/oauth2/**`로 업데이트
