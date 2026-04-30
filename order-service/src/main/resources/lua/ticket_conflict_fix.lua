local bitmapKey = KEYS[1]
local stockKey = KEYS[2]
local tokenKey = KEYS[3]
local seatStartBit = tonumber(ARGV[1])
local totalSectionCount = tonumber(ARGV[2])
local cleanSectionsStr = ARGV[3] or '[]'
local dirtySectionsStr = ARGV[4] or '[]'
local passengerCount = tonumber(ARGV[5]) or 0
local cleanStockSectionsStr = ARGV[6] or '[]'

local function parseList(str)
    local t = {}
    str = string.gsub(str, '[%[%]]', '')
    for num in string.gmatch(str, '%d+') do
        table.insert(t, tonumber(num))
    end
    return t
end

-- 1. 清零干净区间
for _, section in ipairs(parseList(cleanSectionsStr)) do
    local bp = seatStartBit + section - 1
    redis.call('BITFIELD', bitmapKey, 'SET', 'u'..totalSectionCount, bp, 0)
end

-- 2. 标记脏区间
for _, section in ipairs(parseList(dirtySectionsStr)) do
    local bp = seatStartBit + section - 1
    redis.call('BITFIELD', bitmapKey, 'SET', 'u'..totalSectionCount, bp, 1)
end

-- 3. 干净区间库存加回
for _, section in ipairs(parseList(cleanStockSectionsStr)) do
    redis.call('HINCRBY', stockKey, tostring(section), passengerCount)
end

return 1
