-- service_client 소유자(회원 ID) 및 클라이언트 시크릿 해시 컬럼 추가 — 셀프 등록 기능 A

ALTER TABLE service_client
    ADD COLUMN owner_id           BIGINT       NULL,
    ADD COLUMN client_secret_hash VARCHAR(72)  NULL;

COMMENT ON COLUMN service_client.owner_id
    IS '클라이언트 소유자 회원 ID (셀프 등록 시 설정, ADMIN 등록 시 NULL)';

COMMENT ON COLUMN service_client.client_secret_hash
    IS 'BCrypt 해시된 클라이언트 시크릿 (셀프 등록 시 설정, ADMIN 등록 시 NULL). SAS oauth2_registered_client.client_secret과 이중 저장하지 않음.';

-- owner_id별 카운트/조회 인덱스 — countByOwnerId 성능 (1인 5개 제한 검증)
CREATE INDEX idx_service_client_owner_id
    ON service_client (owner_id);
