---
name: springboot-tdd
description: JUnit 5, Mockito, MockMvc, Testcontainers, JaCoCo를 사용한 Spring Boot 테스트 주도 개발. 기능 추가, 버그 수정, 리팩터링에 사용한다.
origin: ECC
---

# Spring Boot TDD 워크플로

80% 이상 커버리지(단위 + 통합)를 갖춘 Spring Boot 서비스를 위한 TDD 가이드.

## 사용 시점

- 새 기능 또는 엔드포인트 추가
- 버그 수정 또는 리팩터링
- 데이터 접근 로직이나 보안 규칙 추가

## 워크플로

1) 테스트를 먼저 작성한다 (실패해야 한다)
2) 통과시킬 최소한의 코드를 구현한다
3) 테스트가 그린 상태에서 리팩터링한다
4) 커버리지를 강제한다 (JaCoCo)

## 단위 테스트 (JUnit 5 + Mockito)

```java
@ExtendWith(MockitoExtension.class)
class MarketServiceTest {
  @Mock MarketRepository repo;
  @InjectMocks MarketService service;

  @Nested
  @DisplayName("마켓 생성 테스트")
  class CreateTest {

    @Test
    @DisplayName("정상적인 마켓 생성")
    void createValidMarket() {
      // given
      CreateMarketRequest req = new CreateMarketRequest("name", "desc", Instant.now(), List.of("cat"));
      when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // when
      Market result = service.create(req);

      // then
      assertThat(result.name()).isEqualTo("name");
      verify(repo).save(any());
    }
  }
}
```

패턴:
- Given-When-Then (주석으로 구분)
- `@Nested` + `@DisplayName`으로 테스트를 한글로 그룹화
- 메서드명은 영문 camelCase, `@DisplayName`은 한글
- 부분 mock 사용을 피하고 명시적 stubbing을 선호한다
- 변형이 많을 때는 `@ParameterizedTest`를 사용한다

## 웹 계층 테스트 (MockMvc)

```java
@WebMvcTest(MarketController.class)
class MarketControllerTest {
  @Autowired MockMvc mockMvc;
  @MockBean MarketService marketService;

  @Nested
  @DisplayName("마켓 목록 조회 테스트")
  class ListTest {

    @Test
    @DisplayName("마켓 목록 정상 조회")
    void listMarkets() throws Exception {
      // given
      when(marketService.list(any())).thenReturn(Page.empty());

      // when & then
      mockMvc.perform(get("/api/markets"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content").isArray());
    }
  }
}
```

## 통합 테스트 (SpringBootTest)

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MarketIntegrationTest {
  @Autowired MockMvc mockMvc;

  @Nested
  @DisplayName("마켓 생성 통합 테스트")
  class CreateIntegrationTest {

    @Test
    @DisplayName("정상적인 마켓 생성 요청 시 201 응답")
    void createMarket() throws Exception {
      // given
      String requestBody = """
          {"name":"Test","description":"Desc","endDate":"2030-01-01T00:00:00Z","categories":["general"]}
          """;

      // when & then
      mockMvc.perform(post("/api/markets")
          .contentType(MediaType.APPLICATION_JSON)
          .content(requestBody))
        .andExpect(status().isCreated());
    }
  }
}
```

## 영속성 테스트 (DataJpaTest)

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestContainersConfig.class)
class MarketRepositoryTest {
  @Autowired MarketRepository repo;

  @Nested
  @DisplayName("마켓 저장 및 조회 테스트")
  class SaveAndFindTest {

    @Test
    @DisplayName("저장 후 이름으로 조회")
    void saveAndFindByName() {
      // given
      MarketEntity entity = new MarketEntity();
      entity.setName("Test");

      // when
      repo.save(entity);
      Optional<MarketEntity> found = repo.findByName("Test");

      // then
      assertThat(found).isPresent();
    }
  }
}
```

## Testcontainers

- 운영 환경을 모사하기 위해 Postgres/Redis 등에 재사용 컨테이너를 사용한다
- `@DynamicPropertySource`로 JDBC URL을 Spring 컨텍스트에 주입한다

## 커버리지 (JaCoCo)

Maven 예시:
```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.14</version>
  <executions>
    <execution>
      <goals><goal>prepare-agent</goal></goals>
    </execution>
    <execution>
      <id>report</id>
      <phase>verify</phase>
      <goals><goal>report</goal></goals>
    </execution>
  </executions>
</plugin>
```

## 단언

- 가독성을 위해 AssertJ(`assertThat`)를 선호한다
- JSON 응답에는 `jsonPath`를 사용한다
- 예외 검증에는 `assertThatThrownBy(...)`를 사용한다

## 테스트 데이터 빌더

```java
class MarketBuilder {
  private String name = "Test";
  MarketBuilder withName(String name) { this.name = name; return this; }
  Market build() { return new Market(null, name, MarketStatus.ACTIVE); }
}
```

## CI 명령

- Maven: `mvn -T 4 test` 또는 `mvn verify`
- Gradle: `./gradlew test jacocoTestReport`

**기억할 점**: 테스트는 빠르고, 격리되어 있으며, 결정적이어야 한다. 구현 세부가 아닌 동작을 테스트한다.
