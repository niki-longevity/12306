local tokenKey = KEYS[1]
local needToken = tonumber(ARGV[1])

local currentToken = tonumber(redis.call('GET', tokenKey) or 0)
if currentToken < needToken then
    return -1
end
return redis.call('DECRBY', tokenKey, needToken)
