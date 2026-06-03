-- =============================================================
-- 선착순 예약 시스템 DDL (MySQL 8 / MariaDB, InnoDB, utf8mb4)
--
-- 권위 경계:
--   MySQL  = 카탈로그 + 주문/결제 영구 권위
--   Redis  = 이벤트 동안 라이브 재고 권위
--            (promo_stock 잔여, sold, inflight, purchased)
--
-- 금액: BIGINT (원, KRW) / 수량: INT
-- 시각: DATETIME(6) — ZonedDateTime (Asia/Seoul 기준으로 앱이 채움)
-- 날짜: DATE / 시간: TIME(6) — 체크인/아웃은 벽시계 시각 (타임존 무관)
-- =============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS point_transaction;
DROP TABLE IF EXISTS payment_line;
DROP TABLE IF EXISTS payment;
DROP TABLE IF EXISTS order_line;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS event_option;
DROP TABLE IF EXISTS event;
DROP TABLE IF EXISTS room_option;
DROP TABLE IF EXISTS product;
DROP TABLE IF EXISTS user_point;
DROP TABLE IF EXISTS users;

-- -------------------------------------------------------------
-- 사용자 (인증 범위 밖 — 식별용 최소 컬럼)
-- -------------------------------------------------------------
CREATE TABLE users (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- 사용자 포인트 잔액 (user_id = PK, 1:1)
-- -------------------------------------------------------------
CREATE TABLE user_point (
    user_id    BIGINT      NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0 COMMENT '가용 포인트(원)',
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_point_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- 상품 (객실타입)
-- -------------------------------------------------------------
CREATE TABLE product (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(255) NOT NULL,
    timezone   VARCHAR(50)  NOT NULL DEFAULT 'Asia/Seoul' COMMENT 'IANA 타임존 (예: Asia/Seoul, Europe/Paris)',
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- 객실 옵션 (객실타입 + 체크인일 + 1박 고정)
-- 체크아웃 날짜는 check_in_date + 1일로 파생 — 저장 안 함
-- stock = 평시 재고 (promo 할당분 제외 잔여)
-- -------------------------------------------------------------
CREATE TABLE room_option (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    product_id     BIGINT      NOT NULL,
    check_in_date  DATE        NOT NULL                COMMENT '체크인 날짜',
    check_in_time  TIME(6)     NOT NULL                COMMENT '입실 벽시계 시각',
    check_out_time TIME(6)     NOT NULL                COMMENT '퇴실 벽시계 시각',
    base_price     BIGINT      NOT NULL                COMMENT '평시 가격(원)',
    stock          INT         NOT NULL DEFAULT 0      COMMENT '평시 재고',
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_room_option_product_date (product_id, check_in_date),
    CONSTRAINT fk_room_option_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- 이벤트 (00시 오픈 프로모션 슬롯)
-- -------------------------------------------------------------
CREATE TABLE event (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(255) NOT NULL,
    starts_at  DATETIME(6)  NOT NULL                   COMMENT '오픈 시각',
    ends_at    DATETIME(6)  NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED|OPEN|CLOSED',
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_event_status_starts (status, starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- 이벤트 옵션 (이벤트 × 객실옵션 — 초특가 + 한정 재고)
-- promo_stock_total = 초기 할당량(=10). 라이브 잔여는 Redis가 권위
-- -------------------------------------------------------------
CREATE TABLE event_option (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    event_id          BIGINT      NOT NULL,
    option_id         BIGINT      NOT NULL,
    promo_price       BIGINT      NOT NULL              COMMENT '초특가(원)',
    promo_stock_total INT         NOT NULL              COMMENT '초기 한정 재고(예:10). Redis seed 원천',
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_event_option (event_id, option_id),
    CONSTRAINT fk_event_option_event  FOREIGN KEY (event_id)  REFERENCES event (id),
    CONSTRAINT fk_event_option_option FOREIGN KEY (option_id) REFERENCES room_option (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- 주문 (idempotency_key UNIQUE = 멱등성 최후 보루)
-- total_amount = gross (상품가). Σpayment_line = total_amount
-- -------------------------------------------------------------
CREATE TABLE orders (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL               COMMENT '클라이언트 발급 멱등키',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|PAID|FAILED|UNKNOWN|CANCELLED',
    total_amount    BIGINT       NOT NULL               COMMENT '상품가 gross (Σorder_line.line_amount)',
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_orders_idempotency_key (idempotency_key),
    KEY idx_orders_user (user_id),
    KEY idx_orders_status_created (status, created_at),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- 주문 라인 (투숙 1건 = 한 객실 한 체크인)
-- promo면 event_option_id 채워짐, 일반 예약이면 NULL
-- check_out_date = check_in_date + nights (Generated Column, 드리프트 없음)
-- -------------------------------------------------------------
CREATE TABLE order_line (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    order_id        BIGINT      NOT NULL,
    room_option_id  BIGINT      NOT NULL,
    event_option_id BIGINT      NULL,
    check_in_date   DATE        NOT NULL,
    check_out_date  DATE        GENERATED ALWAYS AS (DATE_ADD(check_in_date, INTERVAL nights DAY)) STORED
                                COMMENT '파생: check_in_date + nights (수동 저장 금지)',
    nights          INT         NOT NULL DEFAULT 1     COMMENT '현재 구현 1 고정. 연박 확장 시 N',
    unit_price      BIGINT      NOT NULL               COMMENT '예약 시점 가격 스냅샷(원)',
    line_amount     BIGINT      NOT NULL               COMMENT 'unit_price × nights',
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_line_order (order_id),
    CONSTRAINT fk_order_line_order        FOREIGN KEY (order_id)        REFERENCES orders (id),
    CONSTRAINT fk_order_line_room_option  FOREIGN KEY (room_option_id)  REFERENCES room_option (id),
    CONSTRAINT fk_order_line_event_option FOREIGN KEY (event_option_id) REFERENCES event_option (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- 결제 (주문 1:1, PG 결과 보유)
-- amount = net (PG 청구 현금분 = total_amount - 포인트)
-- -------------------------------------------------------------
CREATE TABLE payment (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|SUCCESS|FAILED|UNKNOWN|REFUNDED',
    amount      BIGINT       NOT NULL                   COMMENT 'PG 청구액 net(원)',
    pg_tx_ref   VARCHAR(100) NULL                       COMMENT 'PG 승인 참조번호 (정산 대사 키)',
    fail_reason VARCHAR(255) NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_order (order_id),
    KEY idx_payment_pg_tx_ref (pg_tx_ref),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- 결제 수단별 내역 (복합결제: 수단별 1행, Σamount = orders.total_amount)
-- 결제 확장성의 데이터 토대 — 새 수단 추가 시 이 테이블 구조 변경 없음
-- -------------------------------------------------------------
CREATE TABLE payment_line (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    payment_id BIGINT      NOT NULL,
    method     VARCHAR(20) NOT NULL                     COMMENT 'CREDIT_CARD|PAY|Y_POINT',
    amount     BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_payment_line_payment (payment_id),
    CONSTRAINT fk_payment_line_payment FOREIGN KEY (payment_id) REFERENCES payment (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- 포인트 이력 (USE/REFUND flat 이력 — lot/만료 없음)
-- SUM(USE) - SUM(REFUND) ↔ user_point.balance 정합성 대조
-- -------------------------------------------------------------
CREATE TABLE point_transaction (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    order_id   BIGINT      NOT NULL,
    type       VARCHAR(20) NOT NULL                     COMMENT 'USE|REFUND',
    amount     BIGINT      NOT NULL                     COMMENT '포인트 변동액(원, 양수)',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_point_transaction_user (user_id),
    KEY idx_point_transaction_order (order_id),
    CONSTRAINT fk_point_transaction_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
