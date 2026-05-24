CREATE TABLE members (
  id              BIGINT       GENERATED ALWAYS AS IDENTITY,
  name            VARCHAR(50)  NOT NULL,
  login_id        VARCHAR(20)  NOT NULL,
  hashed_password VARCHAR(72)  NOT NULL,
  generation      INTEGER      NOT NULL,
  status          VARCHAR(2)   NOT NULL,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  CONSTRAINT pk_members PRIMARY KEY (id),
  CONSTRAINT uq_members_login_id UNIQUE (login_id),
  CONSTRAINT chk_members_status CHECK (status IN ('AM', 'RM', 'CM', 'OB')),
  CONSTRAINT chk_members_generation CHECK (generation BETWEEN 1 AND 99)
);

COMMENT ON TABLE  members                  IS 'ECONO 회원 정보';
COMMENT ON COLUMN members.id              IS '회원 식별자 (PK, auto-increment)';
COMMENT ON COLUMN members.name            IS '회원 이름 (한글/영문, 1~50자)';
COMMENT ON COLUMN members.login_id        IS '로그인 식별자 (3~19자, 영숫자·-_·., UNIQUE)';
COMMENT ON COLUMN members.hashed_password IS 'BCrypt 단방향 해시값 (평문 저장 금지)';
COMMENT ON COLUMN members.generation      IS '기수 (ECONO 회원, 1~99)';
COMMENT ON COLUMN members.status          IS '활동 상태: AM | RM | CM | OB';
COMMENT ON COLUMN members.created_at      IS '계정 생성 시각 (timezone-aware)';
