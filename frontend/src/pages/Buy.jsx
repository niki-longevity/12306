import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { buyTicket } from '../api';

export default function Buy() {
  const { state } = useLocation();
  const navigate = useNavigate();
  const train = state?.train || {};
  const [seatType, setSeatType] = useState(2);
  const [passengers, setPassengers] = useState([{ realName: '', idCard: '' }]);
  const [result, setResult] = useState(null);

  const addPassenger = () => setPassengers([...passengers, { realName: '', idCard: '' }]);

  const update = (i, k) => (e) => {
    const arr = [...passengers];
    arr[i][k] = e.target.value;
    setPassengers(arr);
  };

  const handleBuy = async () => {
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
        <button onClick={() => navigate('/orders')}>查看订单</button>
        <button onClick={() => navigate('/tickets')}>继续购票</button>
      </div>
    );
  }

  return (
    <div className="form-page">
      <h2>购票确认</h2>
      <div className="card">
        <p><strong>{train.code}</strong> {state?.start} → {state?.end} {state?.date}</p>
        <p>商务：{train.businessNum}座 ¥{train.businessPrice} | 一等：{train.firstClassNum}座 ¥{train.firstClassPrice} | 二等：{train.secondClassNum}座 ¥{train.secondClassPrice}</p>
      </div>
      <div>
        <label>座位类型：</label>
        <select value={seatType} onChange={e => setSeatType(Number(e.target.value))}>
          <option value={0}>商务座</option><option value={1}>一等座</option><option value={2}>二等座</option>
        </select>
      </div>
      <h3>乘车人</h3>
      {passengers.map((p, i) => (
        <div key={i} className="passenger-row">
          <input placeholder="姓名" value={p.realName} onChange={update(i, 'realName')} />
          <input placeholder="身份证号" value={p.idCard} onChange={update(i, 'idCard')} />
        </div>
      ))}
      <button onClick={addPassenger}>+ 添加乘车人</button>
      <button className="primary" onClick={handleBuy}>确认购买</button>
    </div>
  );
}
