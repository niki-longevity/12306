-- 回滚Redis：位图清零 + 库存加回（与购票Lua镜像对称，幂等可重入）
local bitmapKey = KEYS[1]
local stockKey = KEYS[2]
local seatStartBit = tonumber(ARGV[1]) or 0
local userStartSection = tonumber(ARGV[2]) or 0
local userEndSection = tonumber(ARGV[3]) or 0
local totalSectionCount = tonumber(ARGV[4]) or 0
local passengerCount = tonumber(ARGV[5]) or 0
local sectionsStr = ARGV[6] or ''

-- 解析区间列表
local sections = {}
local ok, res = pcall(cjson.decode, sectionsStr)
if ok and type(res) == 'table' then
    sections = res
else
    sectionsStr = string.gsub(sectionsStr, '[%[%]%s]', '')
    for num in string.gmatch(sectionsStr, '%d+') do
        table.insert(sections, tonumber(num))
    end
end

-- 计算掩码并清零（bit.bnot + bit.band 实现位清零，幂等）
local sectionMask = 0
for i = userStartSection, userEndSection do
    sectionMask = bit.bor(sectionMask, bit.lshift(1, i - 1))
end

local bitFieldCmd = {'BITFIELD', bitmapKey, 'GET', 'u'..totalSectionCount, seatStartBit}
local seatBitmap = redis.call(unpack(bitFieldCmd))[1] or 0

local clearedBitmap = bit.band(seatBitmap, bit.bnot(sectionMask))
redis.call('BITFIELD', bitmapKey, 'SET', 'u'..totalSectionCount, seatStartBit, clearedBitmap)

-- 加回库存（HINCRBY正数，幂等）
for _, section in ipairs(sections) do
    redis.call('HINCRBY', stockKey, tostring(section), passengerCount)
end

return 1
