-- Service Client 및 Route 테이블 — OAuth 클라이언트 관리

CREATE TABLE service_client (
    id                   BIGSERIAL    PRIMARY KEY,
    registered_client_id VARCHAR(100) NOT NULL,
    client_name          VARCHAR(100) NOT NULL,
    grant_type           VARCHAR(30)  NOT NULL,
    api_key_hash         VARCHAR(64)  NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(200) NULL,

    CONSTRAINT uq_service_client_registered_client_id UNIQUE (registered_client_id),
    CONSTRAINT uq_service_client_name UNIQUE (client_name)
);

COMMENT ON TABLE  service_client                          IS 'OAuth 2.0 서비스 클라이언트 메타데이터';
COMMENT ON COLUMN service_client.registered_client_id    IS 'SAS 등록 클라이언트 ID (FK 역할, UNIQUE)';
COMMENT ON COLUMN service_client.client_name             IS '클라이언트 이름 (UNIQUE)';
COMMENT ON COLUMN service_client.grant_type              IS '그랜트 타입 (AUTHORIZATION_CODE | CLIENT_CREDENTIALS)';
COMMENT ON COLUMN service_client.api_key_hash            IS 'SHA-256 해시된 API 키 (client_credentials 전용)';

CREATE TABLE service_route (
    id                   BIGSERIAL    PRIMARY KEY,
    route_id             VARCHAR(100) NOT NULL,
    registered_client_id VARCHAR(100) NOT NULL,
    path_prefix          VARCHAR(200) NULL,
    upstream_url         VARCHAR(500) NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_service_route_route_id    UNIQUE (route_id),
    CONSTRAINT uq_service_route_path_prefix UNIQUE (path_prefix),
    CONSTRAINT fk_service_route_client
        FOREIGN KEY (registered_client_id)
        REFERENCES service_client (registered_client_id)
        ON DELETE CASCADE
);

COMMENT ON TABLE  service_route                     IS 'API Gateway 동적 라우트 설정';
COMMENT ON COLUMN service_route.route_id            IS '라우트 UUID (UNIQUE)';
COMMENT ON COLUMN service_route.registered_client_id IS '연결된 클라이언트 registeredClientId';
COMMENT ON COLUMN service_route.path_prefix         IS '경로 접두사 (UNIQUE, nullable)';
COMMENT ON COLUMN service_route.upstream_url        IS '업스트림 서비스 URL';
COMMENT ON COLUMN service_route.enabled             IS '라우트 활성화 여부';
