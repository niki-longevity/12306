import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { buyTicket } from '../api';

const seatNames = { 0: '商务座', 1: '一等座', 2: '二等座' };

export default function Buy() {
  const { state } = useLocation();
  const navigate = useNavigate();
  const train = state?.train || {};
  const [seatType, setSeatType] = useState(2);
  const [passengers, setPassengers] = useState([{ realName: '', idCard: '' }]);
  const [result, setResult] = useState(null);

  const addPassenger = () => setPassengers([...passengers, { realName: '', idCard: '' }]);
  const removePassenger = (i) => setPassengers(passengers.filter((_, idx) => idx !== i));

  const update = (i, k) => (e) => {
    const arr = [...passengers];
    arr[i][k] = e.target.value;
    setPassengers(arr);
  };

  const handleBuy = async () => {
    for (let i = 0; i < passengers.length; i++) {
      if (!passengers[i].realName || !passengers[i].idCard) {
        alert(`请填写第${i + 1}位乘车人信息`);
        return;
      }
    }
    try {
      const res = await buyTicket({
        date: state.date, code: train.code,
        startStation: state.start, endStation: state.end,
        seatType, passengerList: passengers,
      });
      setResult(res.data);
    } catch (e) { alert('购票失败'); }
  };

  if (result) {
    return (
      <div className="form-page">
        <h2>{result.code === 1 ? '购票成功' : '购票失败'}</h2>
        <p>{result.msg || result.data}</p>
        <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
          <button onClick={() => navigate('/orders')}>查看订单</button>
          <button onClick={() => navigate('/tickets')}>继续购票</button>
        </div>
      </div>
    );
  }

  const prices = [
    { type: 2, name: '二等座', num: train.secondClassNum, price: train.secondClassPrice },
    { type: 1, name: '一等座', num: train.firstClassNum, price: train.firstClassPrice },
    { type: 0, name: '商务座', num: train.businessNum, price: train.businessPrice },
  ];

  return (
    <div className="form-page" style={{ maxWidth: 520 }}>
      <h2>购票确认</h2>

      {/* Train info card */}
      <div style={{ background: '#f8f9fa', borderRadius: 8, padding: 16, marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <span style={{ fontSize: 22, fontWeight: 700, color: '#1a73e8' }}>{train.code}</span>
          <span style={{ fontSize: 13, color: '#666' }}>{state?.date}</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 16 }}>
          <span>{state?.start}</span>
          <span style={{ color: '#999' }}>→</span>
          <span>{state?.end}</span>
        </div>
        <div style={{ fontSize: 13, color: '#999', marginTop: 4 }}>
          {train.startTime} — {train.endTime}
        </div>
      </div>

      {/* Seat type selector */}
      <div style={{ marginBottom: 20 }}>
        <label style={{ fontSize: 14, fontWeight: 600, display: 'block', marginBottom: 8 }}>座位类型</label>
        <div style={{ display: 'flex', gap: 8 }}>
          {prices.map(p => (
            <div key={p.type} onClick={() => setSeatType(p.type)}
              style={{
                flex: 1, padding: '10px 8px', borderRadius: 6, border: '2px solid',
                borderColor: seatType === p.type ? '#1a73e8' : '#ddd',
                background: seatType === p.type ? '#e8f0fe' : '#fff',
                cursor: 'pointer', textAlign: 'center', transition: '0.15s',
              }}>
              <div style={{ fontWeight: 600, fontSize: 14 }}>{p.name}</div>
              <div style={{ color: '#e8870a', fontSize: 16, fontWeight: 700 }}>
                {p.price > 0 ? `¥${p.price}` : '—'}
              </div>
              <div style={{ fontSize: 11, color: p.num > 0 && p.num < 20 ? '#e8870a' : '#999' }}>
                {p.num > 0 ? `${p.num}张` : '售罄'}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Passengers */}
      <div style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <span style={{ fontSize: 14, fontWeight: 600 }}>乘车人</span>
          <button onClick={addPassenger}
            style={{ background: 'none', border: '1px solid #1a73e8', color: '#1a73e8',
                     borderRadius: 4, padding: '4px 12px', fontSize: 13, cursor: 'pointer' }}>
            + 添加
          </button>
        </div>
        {passengers.map((p, i) => (
          <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 8, alignItems: 'center' }}>
            <span style={{ fontSize: 13, color: '#999', minWidth: 20 }}>{i + 1}.</span>
            <input placeholder="姓名" value={p.realName} onChange={update(i, 'realName')}
              style={{ flex: 1, padding: '8px 10px', border: '1px solid #ddd', borderRadius: 4, fontSize: 14 }} />
            <input placeholder="身份证号" value={p.idCard} onChange={update(i, 'idCard')}
              style={{ flex: 2, padding: '8px 10px', border: '1px solid #ddd', borderRadius: 4, fontSize: 14 }} />
            {passengers.length > 1 && (
              <button onClick={() => removePassenger(i)}
                style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: 18, padding: 4 }}>
                ×
              </button>
            )}
          </div>
        ))}
      </div>

      <button className="primary" onClick={handleBuy}
        style={{ width: '100%', padding: 14, fontSize: 16, fontWeight: 600 }}>
        确认购买 · ¥{(prices.find(p => p.type === seatType)?.price || 0) * passengers.length}
      </button>
    </div>
  );
}
