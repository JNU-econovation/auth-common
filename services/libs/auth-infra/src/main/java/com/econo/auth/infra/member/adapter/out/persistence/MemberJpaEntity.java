package com.econo.auth.infra.member.adapter.out.persistence;

import com.econo.auth.core.member.domain.Member;
import com.econo.auth.core.member.domain.MemberStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** members 테이블 JPA 엔티티 */
@Entity
@Table(
		name = "members",
		uniqueConstraints = @UniqueConstraint(name = "uq_members_login_id", columnNames = "login_id"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class MemberJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "name", nullable = false, length = 50)
	private String name;

	@Column(name = "login_id", nullable = false, unique = true, length = 20)
	private String loginId;

	@Column(name = "hashed_password", nullable = false, length = 72)
	private String hashedPassword;

	@Column(name = "generation", nullable = false)
	private Integer generation;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 2)
	private MemberStatus status;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/**
	 * 도메인 Member → JPA 엔티티 변환
	 *
	 * @param member 도메인 Member
	 * @return MemberJpaEntity 인스턴스
	 */
	public static MemberJpaEntity from(Member member) {
		MemberJpaEntity entity = new MemberJpaEntity();
		entity.name = member.getName();
		entity.loginId = member.getLoginId();
		entity.hashedPassword = member.getHashedPassword();
		entity.generation = member.getGeneration();
		entity.status = member.getStatus();
		return entity;
	}

	/**
	 * JPA 엔티티 → 도메인 Member 변환
	 *
	 * @return 도메인 Member 인스턴스
	 */
	public Member toDomain() {
		return Member.restore(id, name, loginId, hashedPassword, generation, status, createdAt);
	}
}
