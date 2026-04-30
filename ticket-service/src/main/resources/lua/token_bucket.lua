local tokenKey = KEYS[1]
local needToken = tonumber(ARGV[1])
local resetToken = tonumber(ARGV[2])

local currentToken = tonumber(redis.call('GET', tokenKey) or 0)
if currentToken < needToken then
    redis.call('SET', tokenKey, resetToken)
    return -1
end
return redis.call('DECRBY', tokenKey, needToken)
