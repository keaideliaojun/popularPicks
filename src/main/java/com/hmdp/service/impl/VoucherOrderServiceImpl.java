package com.hmdp.service.impl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RabbitMQConstant;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    // 初始化一个 lua 脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    // 设置 lua 脚本的属性
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    // 用于创建分布式ID，不泄露订单编号
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RabbitTemplate rabbitTemplate;

    private IVoucherOrderService proxy;

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(50);

    @Override
    public Result seckillVoucher(Long voucherId){

        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = null;
        String stockKey = "sec:s:" + voucherId;
        String orderKey = "sec:o:" + voucherId;
        // 配置Redis汲取使用用于保证这两个key映射到同一个redis实例中
//        String stockKey = "seckill:stock:{seckill:" + voucherId + "}";
//        String orderKey = "seckill:order:{seckill:" + voucherId + "}";
        List<String> keys = Arrays.asList(stockKey, orderKey);
        try {
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
//                    Collections.emptyList(),
                    keys,
//                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId),
                    voucherId.toString()
            );
        } catch (Exception e) {
            log.error("lua脚本执行失败");
            throw new RuntimeException(e);
        }
        int res = result.intValue();
        //2.1 判断执行结果
        if (res != 0) {
            //2.1 结果不为0，没有购买资格
            return Result.fail(res == 1 ? "库存不足":"不可重复购买");
        }

//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setUserId(userId);
//        voucherOrder.setId(orderId);
//        voucherOrder.setVoucherId(voucherId);
//
//        rabbitTemplate.convertAndSend(RabbitMQConstant.SECKILL_VOUCHER_SAVE_QUEUE, voucherOrder);
//        log.info("将订单信息发送到秒杀队列成功：{}", orderId);
        asyncProcessOrder(userId, voucherId);

        return Result.ok("秒杀成功");
    }

    private void asyncProcessOrder(Long userId, Long voucherId) {
        asyncExecutor.submit(() -> {
            try {
                long orderId = redisIdWorker.nextId("order");
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setUserId(userId);
                voucherOrder.setId(orderId);
                voucherOrder.setVoucherId(voucherId);

                // 发送到消息队列
                rabbitTemplate.convertAndSend(RabbitMQConstant.SECKILL_VOUCHER_SAVE_QUEUE, voucherOrder);
                log.info("将订单信息发送到秒杀队列成功：{}", orderId);

            } catch (Exception e) {
                log.error("异步处理订单失败 userId: {}, voucherId: {}", userId, voucherId, e);
            }
        });
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        Long userId = voucherOrder.getUserId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count > 0){
            log.error("用户已购买");
            return;
        }
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                //原本使用CAS（compare and swap）解决超卖的问题，应该是采用.eq(SeckillVoucher::getStock, voucher.getStock())，再查一次与上一次是否一致。
                .update();
        if(!success){
            throw new RuntimeException("秒杀券扣减失败");
//            log.error("已售空");
//            return;
        }
        boolean saved = save(voucherOrder);
        if(!saved){
            throw new RuntimeException("秒杀订单创建失败");
        }
    }
}
