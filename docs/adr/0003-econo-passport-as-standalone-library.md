# ADR-0003: auth-common-lib를 독립 레포(econo-passport)로 분리

- **상태:** Accepted
- **결정일:** 2026-06-01
- **결정자:** econovation 개발팀

---

## 배경

`auth-common` 레포 내에 `services/libs/auth-common-lib`로 공유 라이브러리를 관리했다.
EEOS-BE 등 다른 서비스가 이 라이브러리를 사용하려면 `auth-common` 전체에 의존하거나 JitPack으로 배포해야 했다.

핵심 문제: `spring-boot-starter-web`을 `api` scope로 선언해 Spring MVC를 모든 소비자에 강제 적용 → Reactive 스택인 api-gateway와 충돌.

---

## 결정

**`auth-common-lib`를 [JNU-econovation/econo-passport](https://github.com/JNU-econovation/econo-passport) 독립 레포로 분리하고 JitPack으로 배포한다.**

```kotlin
// 소비 서비스의 build.gradle.kts
repositories { maven("https://jitpack.io") }
implementation("com.github.JNU-econovation:econo-passport:1.0.3")
```

`spring-boot-starter-web`을 `compileOnly`로 변경해 Reactive/MVC 모두 호환.

---

## 근거

1. **라이브러리와 서버의 배포 주기 분리** — 라이브러리 변경이 auth-api 배포를 강제하지 않음
2. **spring-web scope 충돌 해소** — `compileOnly`로 변경해 api-gateway(Reactive) 충돌 제거
3. **독립적인 버전 관리** — 소비 서비스가 원하는 버전을 선택할 수 있음
4. **JitPack으로 간단 배포** — GitHub tag 하나로 배포 완료

---

## 결과

- api-gateway: `project(":libs:auth-common-lib")` → `com.github.JNU-econovation:econo-passport:1.0.3`
- 새 서비스 온보딩: `/use-passport` 스킬로 표준화
- 버전 업 시: econo-passport 레포에 새 tag → JitPack 자동 빌드

---

## 관련 문서

- [econo-passport README](https://github.com/JNU-econovation/econo-passport)
- [use-passport 스킬](../.claude/skills/use-passport/SKILL.md)
