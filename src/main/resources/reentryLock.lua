---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by PC.
--- DateTime: 2025/3/5 18:44
---

local key = KEYS[1];
local threadId = ARGV[1];
local releaseTime = ARGV[2];

if(redis.call('exist', key) == 0) then
    redis.call('hset', key, threadId, '1');
    redis.call('expire', key, releaseTime);
    return 1;
end;
if(redis.call('hexists', key, threadId) == 1) then
    redis.call('hincrby', key, threadId, '1');
    redis.call('expire', key, releaseTime);
end;

return 0;