-- 1. 先解析所有ARGV参数
local bitmapKey = KEYS[1]
local stockKey = KEYS[2]
local seatStartBit = tonumber(ARGV[1]) or 0
local userStartSection = tonumber(ARGV[2]) or 0
local userEndSection = tonumber(ARGV[3]) or 0
local totalSectionCount = tonumber(ARGV[4]) or 0
local passengerCount = tonumber(ARGV[5]) or 0
local sectionsStr = ARGV[6] or ''

-- 2. 解析区间列表
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
if #sections == 0 then
    return -2
end

-- 3. 一次性读取该座位的所有bit位
local bitFieldCmd = {'BITFIELD', bitmapKey, 'GET', 'u'..totalSectionCount, seatStartBit}
local seatBitmap = redis.call(unpack(bitFieldCmd))[1] or 0

-- 4. 生成乘客区间的精准掩码
local sectionMask = 0
for i = userStartSection, userEndSection do
    sectionMask = bit.bor(sectionMask, bit.lshift(1, i - 1))
end

-- 5. 判断：仅乘客区间的bit位全为0才算空闲
if bit.band(seatBitmap, sectionMask) ~= 0 then
    return 0
end

-- 6. 一次性设置乘客区间的bit位为1
local newSeatBitmap = bit.bor(seatBitmap, sectionMask)
redis.call('BITFIELD', bitmapKey, 'SET', 'u'..totalSectionCount, seatStartBit, newSeatBitmap)

-- 7. 扣减库存
for _, section in ipairs(sections) do
    redis.call('HINCRBY', stockKey, tostring(section), -passengerCount)
end

return 1
