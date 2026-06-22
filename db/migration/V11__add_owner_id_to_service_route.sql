-- service_route 소유자(회원 ID) 컬럼 추가 — 라우트 셀프 등록 기능
ALTER TABLE service_route
	ADD COLUMN owner_id BIGINT NULL;

COMMENT ON COLUMN service_route.owner_id
	IS '라우트 소유자 회원 ID (셀프 등록 시 설정, 어드민 등록 시 NULL)';
