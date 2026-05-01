import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { getOrders, cancelOrder } from '../api';

export default function Orders() {
  const [orders, setOrders] = useState([]);
  const navigate = useNavigate();

  useEffect(() => { load(); }, []);

  const load = async () => {
    try {
      const res = await getOrders();
      if (res.data.code === 1) setOrders(res.data.data);
    } catch (e) { alert('加载失败'); }
  };

  const cancel = async (id) => {
    try { await cancelOrder(id); load(); } catch { alert('取消失败'); }
  };

  return (
    <div>
      <h2>我的订单</h2>
      <table>
        <thead><tr><th>订单号</th><th>车次</th><th>日期</th><th>座位</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          {orders.map(o => (
            <tr key={o.id}>
              <td>{o.id}</td>
              <td>{o.trainCode}</td>
              <td>{o.date}</td>
              <td>{o.carriageNum}车{o.seatNum}座</td>
              <td>{o.status}</td>
              <td>
                {o.status === 'UNPAID' && <button onClick={() => navigate(`/pay/${o.id}`, { state: o })}>支付</button>}
                {o.status === 'UNPAID' && <button onClick={() => cancel(o.id)}>取消</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
