package com.hmdp.rabbitmq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RabbitMQConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
@Slf4j
@Component
public class AsyncSaveVoucherListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @RabbitListener(queuesToDeclare = {@Queue(name = RabbitMQConstant.SECKILL_VOUCHER_SAVE_QUEUE)})
    public void AsyncSaveListener(VoucherOrder voucherOrder) {
        log.info("接收到需要保存的订单消息：{}", JSONUtil.toJsonStr(voucherOrder));
        boolean updated = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0).update();
        voucherOrderService.save(voucherOrder);
        log.info("订单信息保存完成？{}", updated);
    }
}
