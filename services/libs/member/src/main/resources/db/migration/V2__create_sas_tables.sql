-- Spring Authorization Server 1.x 표준 DDL (PostgreSQL 방언)
-- 출처: spring-projects/spring-authorization-server
--       oauth2-authorization-server/src/main/resources/org/springframework/security/oauth2/server/authorization/

-- ============================================================
-- oauth2_registered_client
-- ============================================================
CREATE TABLE oauth2_registered_client (
    id                            VARCHAR(100)  NOT NULL,
    client_id                     VARCHAR(100)  NOT NULL,
    client_id_issued_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_secret                 VARCHAR(200)  DEFAULT NULL,
    client_secret_expires_at      TIMESTAMP     DEFAULT NULL,
    client_name                   VARCHAR(200)  NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types     VARCHAR(1000) NOT NULL,
    redirect_uris                 VARCHAR(1000) DEFAULT NULL,
    post_logout_redirect_uris     VARCHAR(1000) DEFAULT NULL,
    scopes                        VARCHAR(1000) NOT NULL,
    client_settings               VARCHAR(2000) NOT NULL,
    token_settings                VARCHAR(2000) NOT NULL,

    CONSTRAINT pk_oauth2_registered_client PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uq_oauth2_registered_client_client_id
    ON oauth2_registered_client (client_id);

COMMENT ON TABLE  oauth2_registered_client                              IS 'SAS OAuth2 클라이언트 등록 정보';
COMMENT ON COLUMN oauth2_registered_client.id                          IS '클라이언트 식별자 (UUID 문자열, PK)';
COMMENT ON COLUMN oauth2_registered_client.client_id                   IS '클라이언트 ID (예: econo-spa), UNIQUE';
COMMENT ON COLUMN oauth2_registered_client.client_id_issued_at         IS '클라이언트 ID 발급 시각';
COMMENT ON COLUMN oauth2_registered_client.client_secret               IS 'BCrypt 해시된 클라이언트 시크릿 (public client는 NULL)';
COMMENT ON COLUMN oauth2_registered_client.client_secret_expires_at    IS '클라이언트 시크릿 만료 시각 (NULL=무기한)';
COMMENT ON COLUMN oauth2_registered_client.client_name                 IS '클라이언트 표시 이름';
COMMENT ON COLUMN oauth2_registered_client.client_authentication_methods IS '인증 방식 (쉼표 구분, 예: none)';
COMMENT ON COLUMN oauth2_registered_client.authorization_grant_types   IS '허용 그랜트 타입 (쉼표 구분)';
COMMENT ON COLUMN oauth2_registered_client.redirect_uris               IS '허용 리다이렉트 URI 목록 (쉼표 구분)';
COMMENT ON COLUMN oauth2_registered_client.post_logout_redirect_uris   IS '로그아웃 후 리다이렉트 URI 목록';
COMMENT ON COLUMN oauth2_registered_client.scopes                      IS '허용 스코프 (쉼표 구분)';
COMMENT ON COLUMN oauth2_registered_client.client_settings             IS '클라이언트 설정 JSON (PKCE 여부 등)';
COMMENT ON COLUMN oauth2_registered_client.token_settings              IS '토큰 설정 JSON (만료 시간 등)';

-- ============================================================
-- oauth2_authorization
-- ============================================================
CREATE TABLE oauth2_authorization (
    id                            VARCHAR(100)  NOT NULL,
    registered_client_id          VARCHAR(100)  NOT NULL,
    principal_name                VARCHAR(200)  NOT NULL,
    authorization_grant_type      VARCHAR(100)  NOT NULL,
    authorized_scopes             VARCHAR(1000) DEFAULT NULL,
    attributes                    TEXT          DEFAULT NULL,
    state                         VARCHAR(500)  DEFAULT NULL,
    authorization_code_value      TEXT          DEFAULT NULL,
    authorization_code_issued_at  TIMESTAMP     DEFAULT NULL,
    authorization_code_expires_at TIMESTAMP     DEFAULT NULL,
    authorization_code_metadata   TEXT          DEFAULT NULL,
    access_token_value            TEXT          DEFAULT NULL,
    access_token_issued_at        TIMESTAMP     DEFAULT NULL,
    access_token_expires_at       TIMESTAMP     DEFAULT NULL,
    access_token_metadata         TEXT          DEFAULT NULL,
    access_token_type             VARCHAR(100)  DEFAULT NULL,
    access_token_scopes           VARCHAR(1000) DEFAULT NULL,
    oidc_id_token_value           TEXT          DEFAULT NULL,
    oidc_id_token_issued_at       TIMESTAMP     DEFAULT NULL,
    oidc_id_token_expires_at      TIMESTAMP     DEFAULT NULL,
    oidc_id_token_metadata        TEXT          DEFAULT NULL,
    refresh_token_value           TEXT          DEFAULT NULL,
    refresh_token_issued_at       TIMESTAMP     DEFAULT NULL,
    refresh_token_expires_at      TIMESTAMP     DEFAULT NULL,
    refresh_token_metadata        TEXT          DEFAULT NULL,
    user_code_value               TEXT          DEFAULT NULL,
    user_code_issued_at           TIMESTAMP     DEFAULT NULL,
    user_code_expires_at          TIMESTAMP     DEFAULT NULL,
    user_code_metadata            TEXT          DEFAULT NULL,
    device_code_value             TEXT          DEFAULT NULL,
    device_code_issued_at         TIMESTAMP     DEFAULT NULL,
    device_code_expires_at        TIMESTAMP     DEFAULT NULL,
    device_code_metadata          TEXT          DEFAULT NULL,

    CONSTRAINT pk_oauth2_authorization PRIMARY KEY (id)
);

COMMENT ON TABLE  oauth2_authorization                              IS 'SAS OAuth2 인가 요청/토큰 영속화';
COMMENT ON COLUMN oauth2_authorization.id                          IS '인가 식별자 (UUID 문자열, PK)';
COMMENT ON COLUMN oauth2_authorization.registered_client_id        IS '클라이언트 ID 참조 (논리적 FK, 제약 없음)';
COMMENT ON COLUMN oauth2_authorization.principal_name              IS '인증 주체 이름 (loginId)';
COMMENT ON COLUMN oauth2_authorization.authorization_grant_type    IS '그랜트 타입';
COMMENT ON COLUMN oauth2_authorization.authorized_scopes           IS '인가된 스코프';
COMMENT ON COLUMN oauth2_authorization.attributes                  IS 'Authentication 직렬화 JSON';
COMMENT ON COLUMN oauth2_authorization.state                       IS 'CSRF 방지 state 값';
COMMENT ON COLUMN oauth2_authorization.authorization_code_value    IS 'Authorization Code 값 (직렬화)';
COMMENT ON COLUMN oauth2_authorization.access_token_value          IS 'Access Token 값 (직렬화)';
COMMENT ON COLUMN oauth2_authorization.oidc_id_token_value         IS 'ID Token 값 (직렬화)';
COMMENT ON COLUMN oauth2_authorization.refresh_token_value         IS 'Refresh Token 값 (직렬화)';
COMMENT ON COLUMN oauth2_authorization.user_code_value             IS 'Device Flow User Code (미사용)';
COMMENT ON COLUMN oauth2_authorization.device_code_value           IS 'Device Flow Device Code (미사용)';

-- ============================================================
-- oauth2_authorization_consent
-- ============================================================
CREATE TABLE oauth2_authorization_consent (
    registered_client_id VARCHAR(100)  NOT NULL,
    principal_name       VARCHAR(200)  NOT NULL,
    authorities          VARCHAR(1000) NOT NULL,

    CONSTRAINT pk_oauth2_authorization_consent
        PRIMARY KEY (registered_client_id, principal_name)
);

COMMENT ON TABLE  oauth2_authorization_consent                          IS 'SAS OAuth2 scope 동의 정보 (requireAuthorizationConsent=false 시 미사용)';
COMMENT ON COLUMN oauth2_authorization_consent.registered_client_id    IS '클라이언트 ID (복합 PK)';
COMMENT ON COLUMN oauth2_authorization_consent.principal_name          IS '인증 주체 이름 (복합 PK)';
COMMENT ON COLUMN oauth2_authorization_consent.authorities             IS '동의된 scope 목록';
