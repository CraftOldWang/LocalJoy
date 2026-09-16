-- KEYS: stock, user->order reservation hash, activity metadata
-- ARGV: userId, orderId. Redis time avoids skew between application instances.
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local beginTime = tonumber(redis.call('hget', KEYS[3], 'begin'))
local endTime = tonumber(redis.call('hget', KEYS[3], 'end'))
if not beginTime or not endTime then return 3 end
if now < beginTime then return 4 end
if now >= endTime then return 5 end
if redis.call('hexists', KEYS[2], ARGV[1]) == 1 then return 2 end
local stock = tonumber(redis.call('get', KEYS[1]) or '-1')
if stock <= 0 then return 1 end
redis.call('decr', KEYS[1])
redis.call('hset', KEYS[2], ARGV[1], ARGV[2])
return 0
