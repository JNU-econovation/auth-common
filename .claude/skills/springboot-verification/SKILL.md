---
name: springboot-verification
description: "Spring Boot 프로젝트의 검증 루프: 빌드, 정적 분석, 커버리지 포함 테스트, 보안 스캔, 릴리스 또는 PR 직전의 diff 리뷰."
origin: ECC
---

# Spring Boot 검증 루프

PR 생성 전, 큰 변경 후, 배포 전에 실행한다.

## 호출 시점

- Spring Boot 서비스의 PR을 열기 전
- 큰 리팩터링이나 의존성 업그레이드 이후
- 스테이징 또는 운영 환경 배포 전 검증
- 전체 빌드 → lint → 테스트 → 보안 스캔 파이프라인 실행
- 테스트 커버리지가 임계치를 충족하는지 확인할 때

## 1단계: 빌드

```bash
mvn -T 4 clean verify -DskipTests
# 또는
./gradlew clean assemble -x test
```

빌드가 실패하면 중단하고 수정한다.

## 2단계: 정적 분석

Maven (자주 쓰는 플러그인):
```bash
mvn -T 4 spotbugs:check pmd:check checkstyle:check
```

Gradle (구성된 경우):
```bash
./gradlew checkstyleMain pmdMain spotbugsMain
```

## 3단계: 테스트 + 커버리지

```bash
mvn -T 4 test
mvn jacoco:report   # 80% 이상 커버리지 확인
# 또는
./gradlew test jacocoTestReport
```

리포트:
- 전체 테스트 수, 통과/실패
- 커버리지 % (라인/분기)

### 단위 테스트

의존성을 mock으로 대체해 서비스 로직을 격리 테스트한다:

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private UserRepository userRepository;
  @InjectMocks private UserService userService;

  @Nested
  @DisplayName("사용자 생성 테스트")
  class CreateUserTest {

    @Test
    @DisplayName("정상 입력 시 사용자 생성 성공")
    void createValidUser() {
      // given
      var dto = new CreateUserDto("Alice", "alice@example.com");
      var expected = new User(1L, "Alice", "alice@example.com");
      when(userRepository.save(any(User.class))).thenReturn(expected);

      // when
      var result = userService.create(dto);

      // then
      assertThat(result.name()).isEqualTo("Alice");
      verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("중복 이메일 시 예외 발생")
    void createDuplicateEmail() {
      // given
      var dto = new CreateUserDto("Alice", "existing@example.com");
      when(userRepository.existsByEmail(dto.email())).thenReturn(true);

      // when & then
      assertThatThrownBy(() -> userService.create(dto))
          .isInstanceOf(DuplicateEmailException.class);
    }
  }
}
```

### Testcontainers 통합 테스트

H2 대신 실제 데이터베이스를 대상으로 테스트한다:

```java
@SpringBootTest
@Testcontainers
class UserRepositoryIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("testdb");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private UserRepository userRepository;

  @Nested
  @DisplayName("이메일 조회 테스트")
  class FindByEmailTest {

    @Test
    @DisplayName("존재하는 사용자 이메일 조회 시 반환")
    void findExistingUser() {
      // given
      userRepository.save(new User("Alice", "alice@example.com"));

      // when
      var found = userRepository.findByEmail("alice@example.com");

      // then
      assertThat(found).isPresent();
      assertThat(found.get().getName()).isEqualTo("Alice");
    }
  }
}
```

### MockMvc API 테스트

전체 Spring 컨텍스트로 컨트롤러 계층을 테스트한다:

```java
@WebMvcTest(UserController.class)
class UserControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private UserService userService;

  @Nested
  @DisplayName("사용자 생성 API 테스트")
  class CreateUserApiTest {

    @Test
    @DisplayName("정상 입력 시 201 응답")
    void createValidUser() throws Exception {
      // given
      var user = new UserDto(1L, "Alice", "alice@example.com");
      when(userService.create(any())).thenReturn(user);

      // when & then
      mockMvc.perform(post("/api/users")
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  {"name": "Alice", "email": "alice@example.com"}
                  """))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.name").value("Alice"));
    }

    @Test
    @DisplayName("잘못된 이메일 형식 시 400 응답")
    void createInvalidEmail() throws Exception {
      // when & then
      mockMvc.perform(post("/api/users")
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  {"name": "Alice", "email": "not-an-email"}
                  """))
          .andExpect(status().isBadRequest());
    }
  }
}
```

## 4단계: 보안 스캔

```bash
# 의존성 CVE
mvn org.owasp:dependency-check-maven:check
# 또는
./gradlew dependencyCheckAnalyze

# 소스 내 시크릿
grep -rn "password\s*=\s*\"" src/ --include="*.java" --include="*.yml" --include="*.properties"
grep -rn "sk-\|api_key\|secret" src/ --include="*.java" --include="*.yml"

# 시크릿 (git 히스토리)
git secrets --scan  # 구성되어 있다면
```

### 흔한 보안 발견 항목

```
# System.out.println 사용 여부 점검 (logger 사용 권장)
grep -rn "System\.out\.print" src/main/ --include="*.java"

# 응답에 원본 예외 메시지가 노출되는지 점검
grep -rn "e\.getMessage()" src/main/ --include="*.java"

# 와일드카드 CORS 점검
grep -rn "allowedOrigins.*\*" src/main/ --include="*.java"
```

## 5단계: Lint/Format (선택적 게이트)

```bash
mvn spotless:apply   # Spotless 플러그인 사용 시
./gradlew spotlessApply
```

## 6단계: Diff 리뷰

```bash
git diff --stat
git diff
```

체크리스트:
- 디버깅 로그가 남아있지 않다 (`System.out`, 가드 없는 `log.debug` 등)
- 의미 있는 오류와 HTTP 상태 코드를 사용한다
- 필요한 곳에 트랜잭션과 검증이 적용되어 있다
- 설정 변경이 문서화되어 있다

## 출력 템플릿

```
검증 리포트
===================
빌드:      [PASS/FAIL]
정적 분석: [PASS/FAIL] (spotbugs/pmd/checkstyle)
테스트:    [PASS/FAIL] (X/Y 통과, Z% 커버리지)
보안:      [PASS/FAIL] (CVE 발견: N건)
Diff:      [X개 파일 변경]

전체:      [READY / NOT READY]

수정할 항목:
1. ...
2. ...
```

## 지속 모드

- 큰 변경이 있을 때 또는 긴 세션에서는 30~60분마다 단계를 다시 실행한다
- 빠른 피드백을 위해 짧은 루프를 유지한다: `mvn -T 4 test` + spotbugs

**기억할 점**: 빠른 피드백이 늦은 사고를 막는다. 게이트는 엄격하게 유지하고, 운영 시스템에서는 경고도 결함으로 취급한다.
