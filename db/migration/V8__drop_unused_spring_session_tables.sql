-- 미사용 Spring Session JDBC 스키마 제거
--
-- V3(V3__create_spring_session_tables.sql)에서 생성한 SPRING_SESSION /
-- SPRING_SESSION_ATTRIBUTES 테이블은 spring-session-jdbc 의존성이 프로젝트에
-- 존재하지 않아(런타임 클래스패스에 spring-session 없음) 사용되지 않는 dead schema이다.
-- auth-api는 표준 서블릿 HttpSession 쿠키 속성만 설정할 뿐 Spring Session JDBC를 쓰지 않는다.

-- 자식 테이블(FK: SPRING_SESSION_ATTRIBUTES → SPRING_SESSION) 먼저 제거
DROP TABLE IF EXISTS SPRING_SESSION_ATTRIBUTES;
DROP TABLE IF EXISTS SPRING_SESSION;
