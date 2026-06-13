-- 회원 권한(role) 컬럼 추가
ALTER TABLE members ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';
