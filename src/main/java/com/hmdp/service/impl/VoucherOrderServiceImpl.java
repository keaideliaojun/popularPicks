package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RabbitTemplate rabbitTemplate;

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final String queueName = "stream.orders";
    private static final String groupName = "g1";

//    @PostConstruct
    private void init(){
        // 创建消息队列
        DefaultRedisScript<Long> mqScript = new DefaultRedisScript<>();
        mqScript.setLocation(new ClassPathResource("stream-mq.lua"));
        mqScript.setResultType(Long.class);
        Long result = null;
        log.info("Queue name: {}, type: {}", queueName, queueName.getClass().getName());
        log.info("Group name: {}, type: {}", groupName, groupName.getClass().getName());
        try {
            result = stringRedisTemplate.execute(mqScript,
                    Collections.emptyList(),
                    queueName,
                    groupName);
        } catch (Exception e) {
            log.error("队列创建失败", e);
            return;
        }
        int r = result.intValue();
        String info = r == 1 ? "队列创建成功" : "队列已存在";
        log.debug(info);
        SECKILL_ORDER_EXECUTOR.submit(new voucherOrderHandler());
    }

    private IVoucherOrderService proxy;

    private class voucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    //1. 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.order
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(groupName, "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2. 判断消息获取是否成功
                    //2.1 如果获取失败，说明没有消息，继续下一次循环
                    if(list == null || list.isEmpty()){
                        continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3. 如果获取成功，可以创建订单
                    handleVoucherOrder(voucherOrder);
                    //4. ACK确认 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, groupName, record.getId());
                    //创建订单

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handPendingList();
                }
            }
        }
    }

    private void handPendingList() {
        while (true) {
            try {
                //1. 获取pending list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.order
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(groupName, "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(1)),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //2. 判断消息获取是否成功
                //2.1 如果获取失败，说明没有异常消息，继续下一次循环
                if(list == null || list.isEmpty()){
                    break;
                }
                //解析消息中的订单信息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                //3. 如果获取成功，可以创建订单
                handleVoucherOrder(voucherOrder);
                //4. ACK确认 XACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(groupName, queueName, record.getId());
                //创建订单

            } catch (Exception e) {
                log.info("处理pending list异常");
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean success = lock.tryLock();
        if (!success) {
            log.error("不允许重复下单");
            return;
        }
        try{
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

//    @Override
    public Result seckillVoucher1(Long voucherId) {
        //1. 执行lua脚本
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = null;
        try {
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId)
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
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok();
    }

    @Override
    public Result seckillVoucher(Long voucherId){

        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = null;
        try {
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId)
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

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);

        rabbitTemplate.convertAndSend(RabbitMQConstant.SECKILL_VOUCHER_SAVE_QUEUE, voucherOrder);
        log.info("将订单信息发送到秒杀队列成功：{}", orderId);

        return Result.ok("秒杀成功");
    }



    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1. 执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        int res = result.intValue();
        //2.1 判断执行结果
        if (res != 0) {
            //2.1 结果不为0，没有购买资格
            return Result.fail(res == 1 ? "库存不足":"不可重复购买");
        }
        //2.2 为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");

        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();


        //返回订单id
        return Result.ok(orderId);
    }*/

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.时间是否符合
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀活动未开始");
        }
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀活动已经结束");
        }
        //3.库存
        if(voucher.getStock() < 1){
            return Result.fail("已售空");
        }

        //4.减库存，创建订单
        Long userId = UserHolder.getUser().getId();
//        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId); //此处将用户id加入锁的key，相当于保证了一人一单和解决超卖问题。
        boolean success = lock.tryLock();
        if(!success){
            //返回错误
            return Result.fail("不允许重复下单");
        }
        try {//AopContext.currentProxy()方法是Spring提供的一个静态方法，用于获取当前线程中正在执行的方法所属的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }  finally {
            lock.unlock();
        }

        *//*boolean success = lock.tryLock(1200);
        if(!success){
            //返回错误
            return Result.fail("不允许重复下单");
        }
        try {//AopContext.currentProxy()方法是Spring提供的一个静态方法，用于获取当前线程中正在执行的方法所属的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }  finally {
            lock.unlock();
        }*//*

    }*/

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
