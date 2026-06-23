# 15-client-self-management - report

## 메타
- **작업명**: 15-client-self-management
- **작성일**: 2026-06-23
- **브랜치**: feat/client-self-management
- **plan 문서**:
  - todo.md
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

ADR-0018(게이트웨이 라우트 셀프 등록을 클라이언트 셀프 등록에 흡수)의 후속으로, 회원이 자기 소유 OAuth 클라이언트(+연결 라우트)를 직접 조회·수정·삭제하는 4개 엔드포인트를 추가했다.

- `GET /api/v1/clients` — 내 클라이언트 목록
- `GET /api/v1/clients/{clientId}` — 상세 (타인/미존재 → 404 CLIENT_NOT_FOUND 존재 은닉)
- `PUT /api/v1/clients/{clientId}` — 전체표현 + 백엔드 diff 수정 (clientName·redirectUris·라우트, 네임스페이스 변경 금지, 라우트 필드 생략=라우트 삭제)
- `DELETE /api/v1/clients/{clientId}` — 하드 삭제 (service_client + SAS + 연결 라우트 캐스케이드 + 게이트웨이 refresh, 204)

## 진행 결과

### 1. test
- 작성된 테스트: 신규 2 파일(`ManageOwnClientServiceTest`, `ServiceClientRepositoryAdapterTest`) + 확장 2 파일(`ServiceRouteRepositoryAdapterTest`, `ClientControllerTest`)
- 시나리오: 서비스 단위(목록 4·상세 4·수정 6·삭제 3), JPA 어댑터(@DataJpaTest), 컨트롤러 MockMvc(목록 4·상세 5·수정 8·삭제 4)
- Red 확인: 미존재 클래스(`ManageOwnClientUseCase`, `ManageOwnClientService`, `RouteNamespaceChangeException`, `ServiceRoute.create` 5-인자) 참조 컴파일 에러로 전 테스트 실패

### 2. implementation
- 신규 8 / 수정 15 코드 파일
- 검증: `./gradlew :services:libs:service-client:test :services:apis:auth-api:test` → Green
- plan과의 차이 없음

### 3. code-review
- 결과: critical 0 / major 3 / minor 3 / 참고 4
- 반영(4): #6 테스트 강화(라우트 매핑 검증), #1 updateMyClient 이중 판별 정리 + updatedAt 주석, #4+#5 컨벤션(@Schema·toList()), #3 예외 정적 팩토리(`RouteNamespaceChangeException.denied`)
- 참고 4건(운영 주석·인터페이스 어노테이션)은 미반영
- 재검증: Green 유지, spotlessCheck 통과

### 4. docs
- 갱신 4 파일: docs/CLIENT_REGISTRATION.md(셀프 관리 API 섹션 신설), docs/DYNAMIC_ROUTING.md(셀프 라우트 수명주기), services/libs/service-client/README.md, services/apis/auth-api/README.md

### 5. doc-review
- 결과: critical 0 / major 4 / minor 2 / 참고 4
- 반영(6): #1+#2 에러표 POST/PUT 정확성 + INVALID_ARGUMENT 추가, #3 "추후 예정"→현재형, #4 DELETE 삭제순서 소유권검증 단계 일치, #5 DUPLICATE_CLIENT_NAME 추가, #6 SasRedirectUriManager 진입점 추가
- 참고 4건 미반영

## 핵심 설계 결정 (구현 반영)

- **클라이언트↔라우트 연관 = service_route.registered_client_id**. owner_id는 회원의 여러 클라이언트(최대 5개) 라우트가 섞여 식별 불가 → 수정·삭제 시 타 클라이언트 라우트 오삭제 결함. 신규 셀프 라우트부터 값 채움(백필 전략 B).
- **clientSecret 미노출**: 어떤 read/update 응답에도 포함하지 않음(해시만 영속, 등록 시 1회 노출).
- **404 존재 은닉**: 타인/미존재 클라이언트는 InvalidClientException → 404 CLIENT_NOT_FOUND.
- **clientName 수정**: 불변 도메인 save의 PK-less INSERT 방지 위해 @Modifying JPQL UPDATE + SAS 동기화.
- **SAS 하드 삭제**: RegisteredClientRepository에 delete 없어 JdbcTemplate 직접 DELETE(SAS 1.x 테이블 의존성 주석).
- **afterCommit 게이트웨이 refresh**: ManageRouteService 패턴 미러링.

## 변경 요약

### 신규 파일 (코드 8)
- service-client: `application/usecase/ManageOwnClientUseCase.java`, `application/service/ManageOwnClientService.java`, `exception/RouteNamespaceChangeException.java`
- auth-api: `presentation/dto/UpdateMyClientRequest.java`, `MyClientItemResponse.java`, `MyClientListResponse.java`, `MyClientRouteInfo.java`
- DB: `db/migration/V13__add_index_service_route_registered_client_id.sql`

### 수정 파일 (코드 15)
- service-client 도메인/엔티티/서비스: `ServiceRoute.java`(registeredClientId 필드+팩토리), `ServiceRouteJpaEntity.java`, `RegisterOAuthClientService.java`
- service-client 포트: `ServiceClientRepository.java`, `ServiceRouteRepository.java`, `SasClientRegistrar.java`
- service-client 어댑터: `ServiceClientJpaRepository.java`, `ServiceClientRepositoryAdapter.java`, `SasClientRegistrarAdapter.java`, `ServiceRouteJpaRepository.java`, `ServiceRouteRepositoryAdapter.java`
- auth-api: `ClientController.java`, `ClientApiDocs.java`, `GlobalExceptionHandler.java`, `ApplicationServiceConfig.java`
- (사전 작업) `SelfRegisterClientRequest.java` — Swagger isRouteFields() @Schema(hidden=true)

### 테스트 (신규 2 / 확장 2)
- `ManageOwnClientServiceTest.java`, `ServiceClientRepositoryAdapterTest.java` (신규)
- `ServiceRouteRepositoryAdapterTest.java`, `ClientControllerTest.java` (확장)

### 갱신 docs (4)
- `docs/CLIENT_REGISTRATION.md`, `docs/DYNAMIC_ROUTING.md`, `services/libs/service-client/README.md`, `services/apis/auth-api/README.md`

## plan과의 차이
- 구현은 plan을 충실히 따랐으며 로직 변경 없음.
- `@Modifying(clearAutomatically=true)`는 JPQL UPDATE 후 영속성 컨텍스트 정합성 확보를 위한 필수 세부사항(plan 우회 아님).

## 운영 주의 (배포 전)
- V13 적용 전 운영 DB에서 `SELECT COUNT(*) FROM service_route WHERE owner_id IS NOT NULL AND registered_client_id IS NULL` 확인 — 0건이면 백필 전략 B 확정, 1건 이상이면 수동 백필/어드민 정리.
- SAS 클라이언트 하드 삭제 시 `oauth2_authorization` 고아 레코드는 자동 청소되지 않음(대량 삭제 빈번해지면 별도 배치 정리 검토 — 이번 범위 밖).

## 인시던트 메모
- 구현/리뷰 반영 중 iCloud Drive 충돌 사본(`* 2.java`/`* 3.java` 소스 + `build/`의 stale `* 2.class`)이 빌드/테스트를 깸. 소스 사본 제거 + 모듈 clean으로 해소. (메모리 icloud-duplicate-files-gotcha 갱신)

## 다음 단계
- /commit 으로 커밋
- /git-pr 로 PR 생성
