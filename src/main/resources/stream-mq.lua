-- 获取传入的参数
local queueName = ARGV[1]
local groupName = ARGV[2]

-- 检查队列是否存在
local queueExists = redis.call('EXISTS', queueName)

if queueExists == 0 then
    -- 队列不存在，创建队列
    redis.call('XADD', queueName, '*', 'dummy', '1')
end

-- 检查消费者组是否存在
local groups = redis.call('XINFO', 'GROUPS', queueName)
local groupExists = false
for _, group in ipairs(groups) do
    if group[2] == groupName then
        groupExists = true
        break
    end
end

if not groupExists then
    -- 消费者组不存在，创建消费者组
    local result = redis.call('XGROUP', 'CREATE', queueName, groupName, '$', 'MKSTREAM')
    if result == 1 then
        return 1 -- 返回 1 表示创建成功
    else
        return 0 -- 返回 0 表示创建失败
    end
else
    -- 消费者组已存在
    return 0 -- 返回 0 表示消费者组已存在
end