package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.ProductOrder;

public interface IProductOrderService extends IService<ProductOrder> {

    Result seckillProduct(Long productId);

    void createProductOrder(ProductOrder productOrder);

    void cancelOrder(Long orderId);

    void sendOrderTimeoutDelayMessage(Long orderId);

    Result payOrder(Long orderId);
    Result queryMyOrder(Long orderId);
    Result queryMyOrders(Integer current);
}
