// Parse buy.csv → generate station_dict + train_template + stopover SQL
const fs = require('fs');
const path = require('path');

const buyCSV = 'C:/Users/15219/Desktop/后端开发/压测/buy.csv';
const outDir = 'D:/Project/java-project/12306Pro/ticket-service/src/main/resources/data';
const outFile = path.join(outDir, 'bench_seed.sql');

// Read and parse buy.csv
const lines = fs.readFileSync(buyCSV, 'utf-8').trim().split('\n').slice(1); // skip header
const trainStations = new Map(); // code → Set of station names
const stationSet = new Set();

for (const line of lines) {
  const [code, start, end] = line.split(',');
  if (!code || !start || !end) continue;
  if (!trainStations.has(code)) trainStations.set(code, new Set());
  trainStations.get(code).add(start);
  trainStations.get(code).add(end);
  stationSet.add(start);
  stationSet.add(end);
}

// Sort stations numerically by suffix
const allStations = [...stationSet].sort((a, b) => {
  const na = parseInt(a.slice(1)), nb = parseInt(b.slice(1));
  return na - nb;
});

console.log(`Trains: ${trainStations.size}, Stations: ${allStations.length}`);

// Build ordered station list per train
function num(s) { return parseInt(s.slice(1)); }
const trainRoutes = new Map(); // code → sorted station array
for (const [code, stSet] of trainStations) {
  const sorted = [...stSet].sort((a, b) => num(a) - num(b));
  trainRoutes.set(code, sorted);
}

// Build SQL
let sql = '';

// 1. Station dict inserts
sql += '-- ===== STATION DICT =====\n';
sql += 'TRUNCATE TABLE station_dict;\n';
for (const s of allStations) {
  const n = s.slice(1); // e.g. "3685" from "S3685"
  let city = 'BenchCity' + (parseInt(n) % 100);
  sql += `INSERT INTO station_dict (station_name, city, province, pinyin, pinyin_abbr, sort_order) VALUES\n`;
  sql += `('${s}', '${city}', 'BenchProv', '${s.toLowerCase()}', '${s.toLowerCase()}', 1);\n`;
}
sql += '\n';

// 2. Train template + stopover
sql += '-- ===== TRAIN TEMPLATES + STOPOVERS =====\n';
sql += 'TRUNCATE TABLE train_template;\n';
sql += 'TRUNCATE TABLE train_template_stopover;\n';

let trainIdx = 0;
for (const [code, stations] of trainRoutes) {
  if (stations.length < 2) continue;
  trainIdx++;

  // Carriage config: mostly second class oriented, randomize a bit
  const hash = code.split('').reduce((s, c) => s + c.charCodeAt(0), 0);
  const businessCars = 1;
  const firstCars = (hash % 2) + 1; // 1 or 2
  const secondCars = (hash % 4) + 5; // 5-8

  sql += `INSERT INTO train_template (train_code, line_code, business_carriage, first_class_carriage, second_class_carriage) VALUES\n`;
  sql += `('${code}', 'L-${code}', ${businessCars}, ${firstCars}, ${secondCars});\n`;

  // Generate times: start at base hour, ~30min between stations
  const baseHour = 6 + (hash % 14); // 6-19 start

  for (let i = 0; i < stations.length; i++) {
    const mileage = i * 80 + (hash % 50);
    const totalMin = (hash % 60) + i * 35;
    const hour = (baseHour + Math.floor(totalMin / 60)) % 24;
    const min = totalMin % 60;
    const time = `${String(hour).padStart(2, '0')}:${String(min).padStart(2, '0')}`;

    const isFirst = (i === 0);
    const isLast = (i === stations.length - 1);
    const inTime = isFirst ? 'NULL' : `'${time}'`;
    const depMin = totalMin + 3;
    const depHour = (baseHour + Math.floor(depMin / 60)) % 24;
    const dm = depMin % 60;
    const outTime = isLast ? 'NULL' : `'${String(depHour).padStart(2,'0')}:${String(dm).padStart(2,'0')}'`;

    sql += `INSERT INTO train_template_stopover (train_code, station_name, station_index, in_time, out_time, mileage) VALUES\n`;
    sql += `('${code}', '${stations[i]}', ${i + 1}, ${inTime}, ${outTime}, ${mileage});\n`;
  }
}

// Write output
fs.writeFileSync(outFile, sql, 'utf-8');
console.log(`Written to: ${outFile}`);
console.log(`SQL size: ${(sql.length / 1024).toFixed(0)} KB`);

// Also print summary stats
let totalStops = 0;
for (const [code, stations] of trainRoutes) totalStops += stations.length;
console.log(`Total stopover rows: ${totalStops}`);
