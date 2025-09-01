package com.econo.common.auth.core.passport;

/**
 * EEOS 시스템의 권한 상수 클래스
 *
 * <p>String 기반 권한 체계에서 타입 안전성을 제공하기 위한 상수 모음입니다.
 *
 * <p>동적 권한 생성을 위한 헬퍼 메서드도 제공합니다.
 *
 * <h2>사용법</h2>
 *
 * <pre>{@code
 * @PassportAuth(requiredRoles = Roles.ADMIN)
 * @PassportAuth(requiredRoles = Roles.departmentAdmin("CS"))
 * }</pre>
 */
public final class Roles {

	// ==================== 기본 권한 ====================

	/** 일반 사용자 권한 */
	public static final String USER = "USER";

	/** 매니저 권한 */
	public static final String MANAGER = "MANAGER";

	/** 관리자 권한 */
	public static final String ADMIN = "ADMIN";

	/** 슈퍼 관리자 권한 */
	public static final String SUPER_ADMIN = "SUPER_ADMIN";

	// ==================== 동적 권한 생성 헬퍼 ====================

	/**
	 * 부서별 관리자 권한 생성
	 *
	 * @param departmentCode 부서 코드 (예: "CS", "EE", "ME")
	 * @return 부서 관리자 권한 (예: "DEPARTMENT_CS_ADMIN")
	 */
	public static String departmentAdmin(String departmentCode) {
		return "DEPARTMENT_" + departmentCode.toUpperCase() + "_ADMIN";
	}

	/**
	 * 부서별 멤버 권한 생성
	 *
	 * @param departmentCode 부서 코드
	 * @return 부서 멤버 권한 (예: "DEPARTMENT_CS_MEMBER")
	 */
	public static String departmentMember(String departmentCode) {
		return "DEPARTMENT_" + departmentCode.toUpperCase() + "_MEMBER";
	}

	/**
	 * 프로젝트별 멤버 권한 생성
	 *
	 * @param projectId 프로젝트 ID
	 * @return 프로젝트 멤버 권한 (예: "PROJECT_2024_MEMBER")
	 */
	public static String projectMember(String projectId) {
		return "PROJECT_" + projectId.toUpperCase() + "_MEMBER";
	}

	/**
	 * 프로젝트별 관리자 권한 생성
	 *
	 * @param projectId 프로젝트 ID
	 * @return 프로젝트 관리자 권한 (예: "PROJECT_2024_ADMIN")
	 */
	public static String projectAdmin(String projectId) {
		return "PROJECT_" + projectId.toUpperCase() + "_ADMIN";
	}

	/**
	 * 이벤트별 스태프 권한 생성
	 *
	 * @param eventName 이벤트명
	 * @return 이벤트 스태프 권한 (예: "EVENT_GRADUATION_STAFF")
	 */
	public static String eventStaff(String eventName) {
		return "EVENT_" + eventName.toUpperCase() + "_STAFF";
	}

	/**
	 * 건물별 관리자 권한 생성
	 *
	 * @param buildingName 건물명
	 * @return 건물 관리자 권한 (예: "BUILDING_학술관_MANAGER")
	 */
	public static String buildingManager(String buildingName) {
		return "BUILDING_" + buildingName.toUpperCase() + "_MANAGER";
	}

	// ==================== 권한 계층 정의 ====================

	/**
	 * 권한 계층을 확인합니다.
	 *
	 * <p>권한 계층: SUPER_ADMIN > ADMIN > MANAGER > USER
	 *
	 * @param userRole 사용자 권한
	 * @param requiredRole 필요한 권한
	 * @return 사용자 권한이 필요한 권한보다 높거나 같으면 true
	 */
	public static boolean hasHigherOrEqualRole(String userRole, String requiredRole) {
		if (userRole == null || requiredRole == null) {
			return false;
		}
		int userLevel = getRoleLevel(userRole);
		int requiredLevel = getRoleLevel(requiredRole);
		return userLevel >= requiredLevel;
	}

	/**
	 * 프로젝트 리더 권한 생성
	 *
	 * @param projectId 프로젝트 ID
	 * @return 프로젝트 리더 권한 (예: "PROJECT_A_LEAD")
	 */
	public static String projectLead(String projectId) {
		return "PROJECT_" + (projectId != null ? projectId.toUpperCase() : "NULL") + "_LEAD";
	}

	/**
	 * 팀 멤버 권한 생성
	 *
	 * @param teamName 팀명
	 * @return 팀 멤버 권한 (예: "TEAM_BACKEND_MEMBER")
	 */
	public static String teamMember(String teamName) {
		return "TEAM_" + (teamName != null ? teamName.toUpperCase() : "") + "_MEMBER";
	}

	/**
	 * 관리자 권한인지 확인
	 *
	 * @param role 권한
	 * @return 관리자 권한이면 true
	 */
	public static boolean isAdminRole(String role) {
		return ADMIN.equals(role)
				|| SUPER_ADMIN.equals(role)
				|| (role != null && role.contains("ADMIN"));
	}

	/**
	 * 매니저 권한인지 확인
	 *
	 * @param role 권한
	 * @return 매니저 권한이면 true
	 */
	public static boolean isManagerRole(String role) {
		return MANAGER.equals(role) || (role != null && role.contains("LEAD"));
	}

	/**
	 * 권한의 레벨을 반환합니다.
	 *
	 * @param role 권한
	 * @return 권한 레벨 (높을수록 상위 권한)
	 */
	public static int getRoleLevel(String role) {
		if (role == null) return 0;
		switch (role) {
			case USER:
				return 1;
			case MANAGER:
				return 2;
			case ADMIN:
				return 3;
			case SUPER_ADMIN:
				return 4;
			default:
				return 0; // 알 수 없는 권한은 최하위
		}
	}

	// ==================== 유틸리티 메서드 ====================

	/**
	 * 기본 권한인지 확인합니다.
	 *
	 * @param role 권한
	 * @return 기본 권한이면 true
	 */
	public static boolean isBasicRole(String role) {
		return USER.equals(role)
				|| MANAGER.equals(role)
				|| ADMIN.equals(role)
				|| SUPER_ADMIN.equals(role);
	}

	/**
	 * 동적 권한인지 확인합니다.
	 *
	 * @param role 권한
	 * @return 동적 권한이면 true
	 */
	public static boolean isDynamicRole(String role) {
		if (role == null) {
			return false;
		}
		return role.startsWith("DEPARTMENT_")
				|| role.startsWith("PROJECT_")
				|| role.startsWith("EVENT_")
				|| role.startsWith("BUILDING_");
	}

	/**
	 * 유효한 권한 형식인지 확인
	 *
	 * @param role 권한
	 * @return 유효한 형식이면 true
	 */
	public static boolean isValidRoleFormat(String role) {
		if (role == null || role.trim().isEmpty()) {
			return false;
		}
		// 대문자로 시작하고 대문자, 숫자, 언더스코어만 허용
		return role.matches("^[A-Z][A-Z0-9_]*$");
	}

	/**
	 * 권한 정규화 (소문자를 대문자로, 공백/하이픈을 언더스코어로)
	 *
	 * @param role 권한
	 * @return 정규화된 권한
	 */
	public static String normalizeRole(String role) {
		if (role == null) {
			return null;
		}
		return role.toUpperCase().replace("-", "_").replace(" ", "_");
	}

	private Roles() {
		// 인스턴스 생성 방지
	}
}
