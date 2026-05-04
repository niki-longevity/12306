import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { queryTickets } from '../api';
import StationPicker from '../components/StationPicker';

const seatNames = { 0: '商务座', 1: '一等座', 2: '二等座' };

export default function TicketList() {
  const [date, setDate] = useState('2026-05-01');
  const [start, setStart] = useState('Beijing');
  const [end, setEnd] = useState('Shanghai');
  const [trains, setTrains] = useState([]);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const search = async () => {
    setLoading(true);
    try {
      const res = await queryTickets(date, start, end);
      if (res.data.code === 1) setTrains(res.data.data);
    } catch (e) { alert('查询失败'); }
    setLoading(false);
  };

  return (
    <div>
      <h2>查票</h2>
      <div className="search-bar">
        <input type="date" value={date} onChange={e => setDate(e.target.value)} />
        <StationPicker value={start} onChange={setStart} placeholder="出发站 · 城市名/站名/拼音" />
        <StationPicker value={end} onChange={setEnd} placeholder="到达站 · 城市名/站名/拼音" />
        <button onClick={search} disabled={loading}>{loading ? '查询中...' : '查询'}</button>
      </div>
      <table>
        <thead><tr><th>车次</th><th>出发</th><th>到达</th><th>商务</th><th>一等</th><th>二等</th><th>操作</th></tr></thead>
        <tbody>
          {trains.map(t => (
            <tr key={t.code}>
              <td>{t.code}</td>
              <td>{t.start} {t.startTime || ''}</td>
              <td>{t.end} {t.endTime || ''}</td>
              <td>{t.businessNum} ¥{t.businessPrice}</td>
              <td>{t.firstClassNum} ¥{t.firstClassPrice}</td>
              <td>{t.secondClassNum} ¥{t.secondClassPrice}</td>
              <td><button onClick={() => navigate('/buy', { state: { train: t, date, start, end } })}>购买</button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
