-- Historical orders intentionally keep NULL snapshots: never invent a historical price.
ALTER TABLE tb_product_order
  ADD COLUMN total_amount bigint NULL COMMENT '下单时金额快照，单位分',
  ADD COLUMN subject varchar(255) NULL COMMENT '下单时商品标题快照';

CREATE TABLE tb_payment_attempt (
  order_id bigint NOT NULL PRIMARY KEY,
  out_trade_no varchar(64) NOT NULL,
  app_id varchar(32) NOT NULL,
  seller_id varchar(32) NOT NULL,
  amount bigint NOT NULL,
  state varchar(32) NOT NULL,
  trade_no varchar(64) NULL,
  qr_code text NULL,
  last_error varchar(128) NULL,
  last_checked_at datetime NULL,
  create_time datetime NOT NULL,
  update_time datetime NOT NULL,
  UNIQUE KEY uk_payment_out_trade_no (out_trade_no),
  UNIQUE KEY uk_payment_trade_no (trade_no),
  KEY idx_payment_reconcile (state, last_checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
