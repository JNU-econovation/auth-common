package com.econo.common.auth.core.passport;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RolesTest {

	@Nested
	@DisplayName("기본 권한 상수 테스트")
	class BasicRolesTest {

		@Test
		@DisplayName("기본 권한 상수가 올바르게 정의되어 있음")
		void basicRoleConstants() {
			assertThat(Roles.USER).isEqualTo("USER");
			assertThat(Roles.MANAGER).isEqualTo("MANAGER");
			assertThat(Roles.ADMIN).isEqualTo("ADMIN");
			assertThat(Roles.SUPER_ADMIN).isEqualTo("SUPER_ADMIN");
		}
	}

	@Nested
	@DisplayName("권한 계층 테스트")
	class RoleHierarchyTest {

		@Test
		@DisplayName("SUPER_ADMIN이 가장 높은 권한")
		void superAdminIsHighest() {
			assertThat(Roles.hasHigherOrEqualRole(Roles.SUPER_ADMIN, Roles.ADMIN)).isTrue();
			assertThat(Roles.hasHigherOrEqualRole(Roles.SUPER_ADMIN, Roles.MANAGER)).isTrue();
			assertThat(Roles.hasHigherOrEqualRole(Roles.SUPER_ADMIN, Roles.USER)).isTrue();
			assertThat(Roles.hasHigherOrEqualRole(Roles.SUPER_ADMIN, Roles.SUPER_ADMIN)).isTrue();
		}

		@Test
		@DisplayName("ADMIN이 MANAGER, USER보다 높은 권한")
		void adminHierarchy() {
			assertThat(Roles.hasHigherOrEqualRole(Roles.ADMIN, Roles.MANAGER)).isTrue();
			assertThat(Roles.hasHigherOrEqualRole(Roles.ADMIN, Roles.USER)).isTrue();
			assertThat(Roles.hasHigherOrEqualRole(Roles.ADMIN, Roles.ADMIN)).isTrue();
			assertThat(Roles.hasHigherOrEqualRole(Roles.ADMIN, Roles.SUPER_ADMIN)).isFalse();
		}

		@Test
		@DisplayName("MANAGER가 USER보다 높은 권한")
		void managerHierarchy() {
			assertThat(Roles.hasHigherOrEqualRole(Roles.MANAGER, Roles.USER)).isTrue();
			assertThat(Roles.hasHigherOrEqualRole(Roles.MANAGER, Roles.MANAGER)).isTrue();
			assertThat(Roles.hasHigherOrEqualRole(Roles.MANAGER, Roles.ADMIN)).isFalse();
			assertThat(Roles.hasHigherOrEqualRole(Roles.MANAGER, Roles.SUPER_ADMIN)).isFalse();
		}

		@Test
		@DisplayName("USER가 가장 낮은 권한")
		void userIsLowest() {
			assertThat(Roles.hasHigherOrEqualRole(Roles.USER, Roles.USER)).isTrue();
			assertThat(Roles.hasHigherOrEqualRole(Roles.USER, Roles.MANAGER)).isFalse();
			assertThat(Roles.hasHigherOrEqualRole(Roles.USER, Roles.ADMIN)).isFalse();
			assertThat(Roles.hasHigherOrEqualRole(Roles.USER, Roles.SUPER_ADMIN)).isFalse();
		}

		@Test
		@DisplayName("알려지지 않은 권한에 대한 계층 확인")
		void unknownRoleHierarchy() {
			assertThat(Roles.hasHigherOrEqualRole("UNKNOWN", Roles.USER)).isFalse();
			assertThat(Roles.hasHigherOrEqualRole(Roles.ADMIN, "UNKNOWN"))
					.isTrue(); // ADMIN(3) >= UNKNOWN(0)
			assertThat(Roles.hasHigherOrEqualRole("UNKNOWN1", "UNKNOWN2"))
					.isTrue(); // UNKNOWN(0) >= UNKNOWN(0)
		}

		@Test
		@DisplayName("null 권한에 대한 처리")
		void nullRoleHandling() {
			assertThat(Roles.hasHigherOrEqualRole(null, Roles.USER)).isFalse();
			assertThat(Roles.hasHigherOrEqualRole(Roles.ADMIN, null)).isFalse();
			assertThat(Roles.hasHigherOrEqualRole(null, null)).isFalse();
		}
	}

	@Nested
	@DisplayName("동적 권한 생성 테스트")
	class DynamicRoleTest {

		@Test
		@DisplayName("부서 관리자 권한 생성")
		void departmentAdmin() {
			assertThat(Roles.departmentAdmin("ENGINEERING")).isEqualTo("DEPARTMENT_ENGINEERING_ADMIN");
			assertThat(Roles.departmentAdmin("marketing")).isEqualTo("DEPARTMENT_MARKETING_ADMIN");
			assertThat(Roles.departmentAdmin("Hr")).isEqualTo("DEPARTMENT_HR_ADMIN");
		}

		@Test
		@DisplayName("프로젝트 리더 권한 생성")
		void projectLead() {
			assertThat(Roles.projectLead("PROJECT_A")).isEqualTo("PROJECT_PROJECT_A_LEAD");
			assertThat(Roles.projectLead("web-app")).isEqualTo("PROJECT_WEB-APP_LEAD");
		}

		@Test
		@DisplayName("팀 멤버 권한 생성")
		void teamMember() {
			assertThat(Roles.teamMember("BACKEND")).isEqualTo("TEAM_BACKEND_MEMBER");
			assertThat(Roles.teamMember("frontend")).isEqualTo("TEAM_FRONTEND_MEMBER");
		}

		@Test
		@DisplayName("빈 문자열이나 null에 대한 처리")
		void emptyOrNullInput() {
			assertThat(Roles.departmentAdmin("")).isEqualTo("DEPARTMENT__ADMIN");
			assertThat(Roles.projectLead(null)).isEqualTo("PROJECT_NULL_LEAD");
			assertThat(Roles.teamMember("")).isEqualTo("TEAM__MEMBER");
		}
	}

	@Nested
	@DisplayName("권한 유틸리티 테스트")
	class RoleUtilityTest {

		@Test
		@DisplayName("관리자 권한 확인")
		void isAdminRole() {
			assertThat(Roles.isAdminRole(Roles.ADMIN)).isTrue();
			assertThat(Roles.isAdminRole(Roles.SUPER_ADMIN)).isTrue();
			assertThat(Roles.isAdminRole(Roles.MANAGER)).isFalse();
			assertThat(Roles.isAdminRole(Roles.USER)).isFalse();
			assertThat(Roles.isAdminRole("DEPARTMENT_ENGINEERING_ADMIN")).isTrue();
			assertThat(Roles.isAdminRole("PROJECT_A_LEAD")).isFalse();
		}

		@Test
		@DisplayName("매니저 권한 확인")
		void isManagerRole() {
			assertThat(Roles.isManagerRole(Roles.MANAGER)).isTrue();
			assertThat(Roles.isManagerRole(Roles.ADMIN)).isFalse();
			assertThat(Roles.isManagerRole(Roles.USER)).isFalse();
			assertThat(Roles.isManagerRole("PROJECT_A_LEAD")).isTrue();
		}

		@Test
		@DisplayName("권한 레벨 가져오기")
		void getRoleLevel() {
			assertThat(Roles.getRoleLevel(Roles.SUPER_ADMIN)).isEqualTo(4);
			assertThat(Roles.getRoleLevel(Roles.ADMIN)).isEqualTo(3);
			assertThat(Roles.getRoleLevel(Roles.MANAGER)).isEqualTo(2);
			assertThat(Roles.getRoleLevel(Roles.USER)).isEqualTo(1);
			assertThat(Roles.getRoleLevel("UNKNOWN")).isEqualTo(0);
			assertThat(Roles.getRoleLevel(null)).isEqualTo(0);
		}
	}

	@Nested
	@DisplayName("권한 검증 테스트")
	class RoleValidationTest {

		@Test
		@DisplayName("유효한 권한 형식 확인")
		void isValidRoleFormat() {
			assertThat(Roles.isValidRoleFormat(Roles.USER)).isTrue();
			assertThat(Roles.isValidRoleFormat("DEPARTMENT_ENGINEERING_ADMIN")).isTrue();
			assertThat(Roles.isValidRoleFormat("PROJECT_A_LEAD")).isTrue();
			assertThat(Roles.isValidRoleFormat("TEAM_BACKEND_MEMBER")).isTrue();

			assertThat(Roles.isValidRoleFormat("")).isFalse();
			assertThat(Roles.isValidRoleFormat(null)).isFalse();
			assertThat(Roles.isValidRoleFormat("invalid-role")).isFalse();
			assertThat(Roles.isValidRoleFormat("123INVALID")).isFalse();
		}

		@Test
		@DisplayName("권한 정규화")
		void normalizeRole() {
			assertThat(Roles.normalizeRole("user")).isEqualTo("USER");
			assertThat(Roles.normalizeRole("Admin")).isEqualTo("ADMIN");
			assertThat(Roles.normalizeRole("MANAGER")).isEqualTo("MANAGER");
			assertThat(Roles.normalizeRole("department-admin")).isEqualTo("DEPARTMENT_ADMIN");
			assertThat(Roles.normalizeRole("project leader")).isEqualTo("PROJECT_LEADER");
		}
	}
}
