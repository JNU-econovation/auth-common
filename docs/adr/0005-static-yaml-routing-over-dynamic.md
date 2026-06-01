# ADR-0005: 정적 YAML 라우팅 채택 (동적 DB 폴링 제거)

- **상태:** Accepted
- **결정일:** 2026-06-01
- **결정자:** econovation 개발팀

---

## 배경

초기에 `service_route` 테이블 기반 동적 라우팅(30초 폴링)을 구현했으나 복잡도 대비 효용이 낮았다.

---

## 결정

**api-gateway의 라우팅을 `application.yml` 정적 설정으로 변경한다.**

```yaml
spring.cloud.gateway.routes:
  - id: auth-api
    uri: ${AUTH_API_URI}
    predicates: [Path=/api/v1/auth/**]
  - id: eeos
    uri: ${EEOS_API_URI}
    predicates: [Path=/api/**]
    filters: [RemoveRequestHeader=Authorization]
```

새 서비스 추가 시 `GatewayRoutingConfig.java` 수정 + 재배포.

---

## 근거

- 동적 라우팅이 필요한 빈도: 매우 낮음 (서비스 추가는 드문 이벤트)
- 폴링으로 인한 복잡도: `DynamicRouteLocator`, `RouteDefinitionCache`, `AuthApiRouteClient` 등 4개 파일 + `@EnableScheduling`
- 30초 지연: 새 서비스 등록 후 최대 30초 지연 허용 불가
- 정적 방식의 장점: 코드 한 줄 추가, 즉시 반영, 디버깅 단순

---

## 결과

- 제거: `DynamicRouteLocator`, `RouteDefinitionCache`, `AuthApiRouteClient`, `RouteDefinition`
- 새 서비스 추가 시 Gateway 재배포 필요 → `/register-service` 스킬로 절차 표준화

### 재검토 조건

서비스가 10개 이상으로 늘어나고 무중단 라우팅 추가가 필요해지면 동적 라우팅 재도입 검토.
