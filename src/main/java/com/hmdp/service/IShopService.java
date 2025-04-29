package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IShopService extends IService<Shop> {

    Shop queryById(Long id);

    Result update(Shop shop);

    Shop queryWithLogicalExpire(Long id);

    Shop queryWithUtil(Long id);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
