# ADR-0008: SAS 인프라 의존을 SasClientRegistrar 포트로 격리

- **상태:** Accepted
- **결정일:** 2026-06-01
- **결정자:** econovation 개발팀

---

## 배경

`RegisterOAuthClientService`(Application 계층)가 Spring Authorization Server의
`RegisteredClientRepository`(인프라 타입)를 직접 주입받아 사용하고 있었다.
Application 계층이 인프라 타입을 직접 알고 있으면 의존성 역전 원칙(DIP) 위반이다.

```java
// 변경 전 — Application이 SAS 인프라 타입에 직접 의존
@Service
class RegisterOAuthClientService {
    private final RegisteredClientRepository sasRepo; // ← SAS 인프라
    private final RegisteredClient.Builder ...        // ← SAS 도메인 객체 빌드 로직도 서비스에
}
```

---

## 결정

**`SasClientRegistrar` 아웃바운드 포트를 Application 계층에 신설하고, SAS 구현체는 `SasClientRegistrarAdapter`(adapter/out/sas)에 격리한다.**

```java
// application/port/out/SasClientRegistrar.java
public interface SasClientRegistrar {
    void registerAuthorizationCodeClient(String clientId, String clientName, Set<String> redirectUris);
    void registerClientCredentialsClient(String clientId, String clientName, String bcryptHashedSecret);
}

// adapter/out/sas/SasClientRegistrarAdapter.java — RegisteredClient 빌드 로직 여기만
@Component
class SasClientRegistrarAdapter implements SasClientRegistrar {
    private final RegisteredClientRepository registeredClientRepository;
    ...
}
```

---

## 근거

1. **DIP 준수**: Application → Port(인터페이스) ← Infrastructure. SAS 라이브러리 교체 시 Adapter만 변경하면 됨
2. **테스트 용이성**: `RegisterOAuthClientServiceTest`에서 SAS 없이 Mockito로 포트만 모킹하면 됨
3. **책임 분리**: `RegisteredClient` 객체 빌드 로직(SAS 전용 지식)이 Adapter에만 존재

---

## 결과

- `RegisterOAuthClientServiceTest`에서 `@Mock RegisteredClientRepository` → `@Mock SasClientRegistrar`로 교체
- `RegisteredClientConfig`는 `RegisteredClientRepository` 빈만 등록, Adapter가 이를 주입받아 사용
- SAS 의존성(`spring-boot-starter-oauth2-authorization-server`)은 Adapter 패키지 밖에서 사용 안 됨

---

## 관련 문서

- [ADR-0003](./0003-econo-passport-as-standalone-library.md)
