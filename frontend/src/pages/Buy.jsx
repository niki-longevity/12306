import { useState, useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { buyTicket, getPassengers } from '../api';
import { useToast } from '../components/Toast';

const seatNames = { 0: '商务座', 1: '一等座', 2: '二等座' };
const TYPE_LABELS = { ADULT: '成人', STUDENT: '学生', CHILD: '儿童' };
const TYPE_COLORS = { ADULT: '#1a73e8', STUDENT: '#d32f2f', CHILD: '#2e7d32' };

export default function Buy() {
  const { state } = useLocation();
  const navigate = useNavigate();
  const toast = useToast();
  const train = state?.train || {};
  const [seatType, setSeatType] = useState(2);
  const [savedPassengers, setSavedPassengers] = useState([]);
  const [selectedIds, setSelectedIds] = useState(new Set());
  const [manualMode, setManualMode] = useState(false);
  const [manualPassengers, setManualPassengers] = useState([{ realName: '', idCard: '' }]);
  const [result, setResult] = useState(null);

  useEffect(() => {
    getPassengers().then(res => {
      if (res.data.code === 1) setSavedPassengers(res.data.data || []);
    }).catch(() => {});
  }, []);

  const toggleSelect = (p) => {
    const next = new Set(selectedIds);
    if (next.has(p.id)) next.delete(p.id);
    else next.add(p.id);
    setSelectedIds(next);
  };

  const selectedPassengers = savedPassengers.filter(p => selectedIds.has(p.id));

  const addManual = () => setManualPassengers([...manualPassengers, { realName: '', idCard: '' }]);
  const removeManual = (i) => setManualPassengers(manualPassengers.filter((_, idx) => idx !== i));
  const updateManual = (i, k) => (e) => {
    const arr = [...manualPassengers];
    arr[i][k] = e.target.value;
    setManualPassengers(arr);
  };

  const getFinalPassengers = () => {
    const fromSaved = selectedPassengers.map(p => ({ realName: p.realName, idCard: p.idCard }));
    if (manualMode) return fromSaved.concat(manualPassengers);
    return fromSaved;
  };

  const handleBuy = async () => {
    const passengers = getFinalPassengers();
    if (passengers.length === 0) {
      toast.show('请至少选择一位乘车人', 'error');
      return;
    }
    for (let i = 0; i < passengers.length; i++) {
      if (!passengers[i].realName || !passengers[i].idCard) {
        toast.show(`请填写第${i + 1}位乘车人信息`, 'error');
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
    } catch { toast.show('购票失败', 'error'); }
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
  const selectedPrice = prices.find(p => p.type === seatType)?.price || 0;

  return (
    <div className="form-page" style={{ maxWidth: 560 }}>
      <h2>购票确认</h2>

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
        <div style={{ fontSize: 13, color: '#999', marginTop: 4 }}>{train.startTime} — {train.endTime}</div>
      </div>

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

      <div style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <span style={{ fontSize: 14, fontWeight: 600 }}>选择乘车人</span>
          <div style={{ display: 'flex', gap: 8 }}>
            <button onClick={() => setManualMode(!manualMode)}
              style={{ background: 'none', border: '1px solid #1a73e8', color: '#1a73e8', borderRadius: 4, padding: '4px 12px', fontSize: 13, cursor: 'pointer' }}>
              {manualMode ? '关闭手动输入' : '+ 手动输入'}
            </button>
            <button onClick={() => navigate('/passengers')}
              style={{ background: 'none', border: '1px solid #1a73e8', color: '#1a73e8', borderRadius: 4, padding: '4px 12px', fontSize: 13, cursor: 'pointer' }}>
              管理乘车人
            </button>
          </div>
        </div>

        {savedPassengers.length > 0 ? (
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
            {savedPassengers.map(p => (
              <div key={p.id} onClick={() => toggleSelect(p)}
                style={{
                  padding: '8px 14px', borderRadius: 6, border: '2px solid',
                  borderColor: selectedIds.has(p.id) ? '#1a73e8' : '#ddd',
                  background: selectedIds.has(p.id) ? '#e8f0fe' : '#fff',
                  cursor: 'pointer', fontSize: 13, transition: '0.15s',
                }}>
                <strong>{p.realName}</strong>
                <span style={{ color: TYPE_COLORS[p.passengerType] || '#666', fontSize: 11, marginLeft: 6 }}>
                  {TYPE_LABELS[p.passengerType] || p.passengerType}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <div style={{ padding: 16, background: '#f8f9fa', borderRadius: 6, fontSize: 13, color: '#999', textAlign: 'center', marginBottom: 12 }}>
            暂无已保存的乘车人，请手动输入或先
            <span onClick={() => navigate('/passengers')} style={{ color: '#1a73e8', cursor: 'pointer' }}>添加乘车人</span>
          </div>
        )}

        {selectedPassengers.length > 0 && (
          <div style={{ background: '#f8f9fa', padding: 12, borderRadius: 6, fontSize: 13, marginBottom: 12 }}>
            <div style={{ fontWeight: 600, marginBottom: 6, fontSize: 12, color: '#666' }}>已选乘车人：</div>
            {selectedPassengers.map((p, i) => (
              <div key={p.id} style={{ marginBottom: 4 }}>
                {i + 1}. {p.realName} — {p.idCard.substring(0, 4)}****{p.idCard.substring(14)} — {TYPE_LABELS[p.passengerType]}
              </div>
            ))}
          </div>
        )}

        {manualMode && manualPassengers.map((p, i) => (
          <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 8, alignItems: 'center' }}>
            <span style={{ fontSize: 13, color: '#999', minWidth: 20 }}>{i + 1}.</span>
            <input placeholder="姓名" value={p.realName} onChange={updateManual(i, 'realName')}
              style={{ flex: 1, padding: '8px 10px', border: '1px solid #ddd', borderRadius: 4, fontSize: 14 }} />
            <input placeholder="身份证号" value={p.idCard} onChange={updateManual(i, 'idCard')}
              style={{ flex: 2, padding: '8px 10px', border: '1px solid #ddd', borderRadius: 4, fontSize: 14 }} />
            {manualPassengers.length > 1 && (
              <button onClick={() => removeManual(i)}
                style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: 18, padding: 4 }}>×</button>
            )}
          </div>
        ))}
        {manualMode && (
          <button onClick={addManual}
            style={{ background: 'none', border: '1px dashed #1a73e8', color: '#1a73e8', borderRadius: 4, padding: '6px 12px', fontSize: 13, cursor: 'pointer', marginBottom: 8 }}>
            + 添加
          </button>
        )}
      </div>

      <button className="primary" onClick={handleBuy}
        style={{ width: '100%', padding: 14, fontSize: 16, fontWeight: 600 }}>
        确认购买 · ¥{selectedPrice * Math.max(getFinalPassengers().length, 1)}
      </button>
    </div>
  );
}
