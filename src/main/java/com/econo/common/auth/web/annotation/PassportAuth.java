package com.econo.common.auth.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 메서드 파라미터에 Passport를 주입하기 위한 어노테이션
 *
 * <p>Spring Security의 패러다임을 따라 String 기반 권한 체계를 사용합니다. 이는 동적 권한 생성, 외부 시스템 연동, 미래 확장성을 고려한 설계입니다.
 *
 * <h2>기본 사용법</h2>
 *
 * <pre>{@code
 * @GetMapping("/api/programs")
 * public ResponseEntity<List<Program>> getPrograms(@PassportAuth Passport passport) {
 *     return programService.getUserPrograms(passport.getMemberId());
 * }
 * }</pre>
 *
 * <h2>권한 검증</h2>
 *
 * <pre>{@code
 * @GetMapping("/api/admin/users")
 * public ResponseEntity<List<User>> getAllUsers(
 *         @PassportAuth(requiredRoles = "ADMIN") Passport passport) {
 *     return userService.getAllUsers();
 * }
 * }</pre>
 *
 * <h2>다중 권한 (OR 조건)</h2>
 *
 * <pre>{@code
 * @GetMapping("/api/manage")
 * public ResponseEntity<String> manage(
 *         @PassportAuth(requiredRoles = {"ADMIN", "MANAGER"}) Passport passport) {
 *     return ResponseEntity.ok("관리 페이지");
 * }
 * }</pre>
 *
 * <h2>모든 권한 필요 (AND 조건)</h2>
 *
 * <pre>{@code
 * @DeleteMapping("/api/system/reset")
 * public ResponseEntity<Void> resetSystem(
 *         @PassportAuth(
 *             requiredRoles = {"ADMIN", "SUPER_USER"},
 *             requireAllRoles = true
 *         ) Passport passport) {
 *     systemService.reset();
 *     return ResponseEntity.ok().build();
 * }
 * }</pre>
 *
 * <h2>권한 계층 지원</h2>
 *
 * <pre>{@code
 * @GetMapping("/api/manage")
 * public ResponseEntity<String> manage(
 *         @PassportAuth(
 *             requiredRoles = "MANAGER",
 *             includeHigherRoles = true
 *         ) Passport passport) {
 *     // MANAGER 권한뿐만 아니라 ADMIN도 접근 가능
 *     return ResponseEntity.ok("관리 페이지");
 * }
 * }</pre>
 *
 * <h2>조건부 권한 (SpEL)</h2>
 *
 * <pre>{@code
 * @GetMapping("/api/users/{userId}")
 * public ResponseEntity<User> getUser(
 *         @PathVariable Long userId,
 *         @PassportAuth(
 *             condition = "#{passport.memberId == #userId or passport.isAdmin()}"
 *         ) Passport passport) {
 *     // 본인이거나 관리자만 접근 가능
 *     return ResponseEntity.ok(userService.getUser(userId));
 * }
 * }</pre>
 *
 * <h2>선택적 인증</h2>
 *
 * <pre>{@code
 * @GetMapping("/api/public/programs")
 * public ResponseEntity<List<Program>> getPublicPrograms(
 *         @PassportAuth(required = false) Passport passport) {
 *     if (passport != null) {
 *         return ResponseEntity.ok(programService.getUserPrograms(passport.getMemberId()));
 *     } else {
 *         return ResponseEntity.ok(programService.getPublicPrograms());
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PassportAuth {
	/**
	 * Passport가 필수인지 여부
	 *
	 * @return false일 경우 Passport가 없어도 null로 주입됨 (기본값: true)
	 */
	boolean required() default true;

	/**
	 * 만료 검증 여부
	 *
	 * @return true일 경우 만료된 Passport는 예외 발생 (기본값: true)
	 */
	boolean validateExpiry() default true;

	/**
	 * 필요한 권한들
	 *
	 * <p>Spring Security의 패러다임을 따라 String 기반으로 관리합니다. 이는 동적 권한 생성과 외부 시스템 연동을 위한 설계입니다.
	 *
	 * <h4>권한 명명 규칙:</h4>
	 *
	 * <ul>
	 *   <li>기본 권한: {@code "USER", "ADMIN", "MANAGER"}
	 *   <li>부서 권한: {@code "DEPARTMENT_CS_ADMIN"}
	 *   <li>프로젝트 권한: {@code "PROJECT_2024_MEMBER"}
	 *   <li>이벤트 권한: {@code "EVENT_GRADUATION_STAFF"}
	 * </ul>
	 *
	 * @return 빈 배열이면 권한 체크하지 않음 (기본값: 빈 배열)
	 */
	String[] requiredRoles() default {};

	/**
	 * 모든 권한이 필요한지 여부
	 *
	 * @return true일 경우 requiredRoles의 모든 권한을 보유해야 함 (AND 조건) false일 경우 하나의 권한만 있어도 됨 (OR 조건, 기본값)
	 */
	boolean requireAllRoles() default false;

	/**
	 * 권한 계층을 고려할지 여부
	 *
	 * <p>true일 경우 상위 권한을 가진 사용자도 접근할 수 있습니다.
	 *
	 * <p>권한 계층: ADMIN > MANAGER > USER
	 *
	 * <h4>예시:</h4>
	 *
	 * <pre>{@code
	 * @PassportAuth(requiredRoles = "MANAGER", includeHigherRoles = true)
	 * // MANAGER 권한뿐만 아니라 ADMIN도 접근 가능
	 * }</pre>
	 *
	 * @return 상위 권한 포함 여부 (기본값: false)
	 */
	boolean includeHigherRoles() default false;

	/**
	 * 조건부 권한 검증을 위한 Spring Expression Language (SpEL) 표현식
	 *
	 * <p>복잡한 권한 로직을 SpEL로 표현할 수 있습니다.
	 *
	 * <h4>사용 가능한 변수:</h4>
	 *
	 * <ul>
	 *   <li>{@code passport}: 현재 Passport 객체
	 *   <li>{@code #매개변수명}: 메서드의 다른 매개변수들
	 * </ul>
	 *
	 * <h4>예시:</h4>
	 *
	 * <pre>{@code
	 * // 본인이거나 관리자만 접근
	 * condition = "#{passport.memberId == #userId or passport.isAdmin()}"
	 *
	 * // 특정 역할이면서 특정 조건을 만족
	 * condition = "#{passport.hasRole('MANAGER') and #project.departmentId == passport.departmentId}"
	 * }</pre>
	 *
	 * @return SpEL 표현식 (기본값: 빈 문자열 - 조건 없음)
	 */
	String condition() default "";
}
