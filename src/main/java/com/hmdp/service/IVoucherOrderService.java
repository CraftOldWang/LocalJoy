package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 超时关单：取消未支付订单并回滚库存
     *
     * @param orderId 订单ID
     */
    void cancelOrder(Long orderId);

    /**
     * 发送超时关单延时消息
     *
     * @param orderId 订单ID
     */
    void sendOrderTimeoutDelayMessage(Long orderId);

}
