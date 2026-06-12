import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { getOrders, cancelOrder } from '../api';

const statusLabel = { UNPAID: '待支付', PAID: '已支付', CANCELLED: '已取消' };
const statusColor = { UNPAID: '#e8870a', PAID: '#2e7d32', CANCELLED: '#999' };
const seatNames = { 0: '商务座', 1: '一等座', 2: '二等座' };

export default function Orders() {
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => { load(); }, []);

  const load = async () => {
    setLoading(true);
    try {
      const res = await getOrders();
      if (res.data.code === 1) setOrders(res.data.data || []);
    } catch (e) { alert('加载失败'); }
    setLoading(false);
  };

  const cancel = async (id) => {
    if (!confirm('确认取消/退票？')) return;
    try {
      const res = await cancelOrder(id);
      if (res.data.code === 1) load();
      else alert(res.data.msg || '操作失败');
    } catch { alert('网络错误'); }
  };

  return (
    <div>
      <h2>我的订单</h2>
      {loading ? (
        <p style={{ color: '#999', marginTop: 16 }}>加载中...</p>
      ) : orders.length === 0 ? (
        <p style={{ color: '#999', marginTop: 16 }}>暂无订单</p>
      ) : (
        <table>
          <thead><tr><th>车次</th><th>日期</th><th>出发→到达</th><th>座位</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            {orders.map(o => (
              <tr key={o.id}>
                <td><strong>{o.trainCode}</strong></td>
                <td>{o.date}</td>
                <td>{o.startStation} → {o.endStation}</td>
                <td>{seatNames[o.seatType] || '—'} {o.carriageNum}车{o.seatNum}座</td>
                <td><span style={{ color: statusColor[o.status] || '#999', fontWeight: 600 }}>
                  {statusLabel[o.status] || o.status}
                </span></td>
                <td style={{ display: 'flex', gap: 8 }}>
                  {o.status === 'UNPAID' && (
                    <button onClick={() => navigate(`/pay/${o.id}`, { state: o })}>支付</button>
                  )}
                  {o.status === 'UNPAID' && (
                    <button onClick={() => cancel(o.id)}>取消订单</button>
                  )}
                  {o.status === 'PAID' && (
                    <button onClick={() => cancel(o.id)} style={{ background: '#e8870a' }}>退票</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
