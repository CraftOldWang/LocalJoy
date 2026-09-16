ALTER TABLE tb_product_order
  ADD COLUMN expire_time datetime NULL COMMENT '支付截止时间',
  ADD COLUMN active_user_id bigint GENERATED ALWAYS AS
    (CASE WHEN status <> 4 THEN user_id ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_product_active_user (product_id, active_user_id),
  ADD KEY idx_product_order_expire (status, expire_time);

UPDATE tb_product_order SET expire_time = DATE_ADD(create_time, INTERVAL 30 MINUTE)
WHERE expire_time IS NULL;
