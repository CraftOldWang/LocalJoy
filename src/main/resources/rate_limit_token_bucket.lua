local key = KEYS[1]
local now = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local refillTokens = tonumber(ARGV[3])
local refillInterval = tonumber(ARGV[4])
local permits = tonumber(ARGV[5])

local tokens = tonumber(redis.call('hget', key, 'tokens') or capacity)
local last = tonumber(redis.call('hget', key, 'last') or now)
local delta = math.max(0, now - last)
local rounds = math.floor(delta / refillInterval)

if rounds > 0 then
    tokens = math.min(capacity, tokens + rounds * refillTokens)
    last = last + rounds * refillInterval
end

if tokens < permits then
    redis.call('hmset', key, 'tokens', tokens, 'last', last)
    redis.call('pexpire', key, math.max(refillInterval, math.ceil(capacity / refillTokens) * refillInterval))
    return 0
end

tokens = tokens - permits
redis.call('hmset', key, 'tokens', tokens, 'last', last)
redis.call('pexpire', key, math.max(refillInterval, math.ceil(capacity / refillTokens) * refillInterval))
return 1
