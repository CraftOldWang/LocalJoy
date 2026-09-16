-- Only the owner of this reservation can restore stock or release eligibility.
-- A duplicate/late rollback must never undo a newer order by the same user.
if redis.call('hget', KEYS[2], ARGV[1]) ~= ARGV[2] then return 0 end
if redis.call('exists', KEYS[1]) == 0 then
    return redis.error_reply('Stock missing: reconciliation required')
end
redis.call('incr', KEYS[1])
if ARGV[3] and ARGV[3] ~= '' then
    redis.call('hset', KEYS[2], ARGV[1], ARGV[3])
else
    redis.call('hdel', KEYS[2], ARGV[1])
end
return 1
