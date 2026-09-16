-- 给 tb_voucher_order 表添加 close_reason 字段，用于记录关单原因
ALTER TABLE `tb_voucher_order`
    ADD COLUMN `close_reason` VARCHAR(64) NULL DEFAULT NULL COMMENT '关单原因，如 TIMEOUT_AUTO_CLOSE' AFTER `update_time`;
