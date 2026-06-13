-- service_client.grant_type NOT NULL 제약 제거
-- grantType optional 전환 (OAuth 재도입 대비 컬럼 보존)
ALTER TABLE service_client
    ALTER COLUMN grant_type DROP NOT NULL;

COMMENT ON COLUMN service_client.grant_type
    IS '그랜트 타입 (AUTHORIZATION_CODE | CLIENT_CREDENTIALS | NULL=client_credentials 디폴트 처리)';
