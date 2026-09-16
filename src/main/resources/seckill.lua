local stockKey
local orderKey
local userId
if (ARGV[3] ~= nil) then
    stockKey = ARGV[1]
    orderKey = ARGV[2]
    userId = ARGV[3]
else
    local voucherId = ARGV[1]
    userId = ARGV[2]
    stockKey = 'seckill:stock:' .. voucherId
    orderKey = 'seckill:order:' .. voucherId
end
-- 判断库存是否充足
local stock = tonumber(redis.call('get', stockKey) or '-1')
if (stock <= 0) then
    return 1
end
-- 判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end
-- 扣减库存
redis.call('incrby', stockKey, -1)
-- 将userId存入当前商品/优惠券的set集合
redis.call('sadd', orderKey, userId)
return 0
