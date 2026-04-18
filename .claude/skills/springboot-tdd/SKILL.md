---
name: springboot-tdd
description: Test-driven development for Spring Boot using JUnit 5, Mockito, MockMvc, Testcontainers, and JaCoCo. Use when adding features, fixing bugs, or refactoring.
origin: ECC
---

# Spring Boot TDD Workflow

TDD guidance for Spring Boot services with 80%+ coverage (unit + integration).

## When to Use

- New features or endpoints
- Bug fixes or refactors
- Adding data access logic or security rules

## Workflow

1) Write tests first (they should fail)
2) Implement minimal code to pass
3) Refactor with tests green
4) Enforce coverage (JaCoCo)

## Unit Tests (JUnit 5 + Mockito)

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

Patterns:
- Given-When-Then (주석으로 구분)
- `@Nested` + `@DisplayName` 한글로 테스트 그룹화
- 메서드명은 영문 camelCase, `@DisplayName`은 한글
- Avoid partial mocks; prefer explicit stubbing
- Use `@ParameterizedTest` for variants

## Web Layer Tests (MockMvc)

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

## Integration Tests (SpringBootTest)

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

## Persistence Tests (DataJpaTest)

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

- Use reusable containers for Postgres/Redis to mirror production
- Wire via `@DynamicPropertySource` to inject JDBC URLs into Spring context

## Coverage (JaCoCo)

Maven snippet:
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

## Assertions

- Prefer AssertJ (`assertThat`) for readability
- For JSON responses, use `jsonPath`
- For exceptions: `assertThatThrownBy(...)`

## Test Data Builders

```java
class MarketBuilder {
  private String name = "Test";
  MarketBuilder withName(String name) { this.name = name; return this; }
  Market build() { return new Market(null, name, MarketStatus.ACTIVE); }
}
```

## CI Commands

- Maven: `mvn -T 4 test` or `mvn verify`
- Gradle: `./gradlew test jacocoTestReport`

**Remember**: Keep tests fast, isolated, and deterministic. Test behavior, not implementation details.
