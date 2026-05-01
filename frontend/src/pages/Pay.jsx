import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { payOrder } from '../api';

export default function Pay() {
  const { orderId } = useParams();
  const navigate = useNavigate();
  const [paid, setPaid] = useState(false);
  const [error, setError] = useState('');

  const handlePay = async () => {
    try {
      const res = await payOrder(orderId);
      if (res.data.code === 1) setPaid(true);
      else setError(res.data.msg);
    } catch { setError('支付失败'); }
  };

  if (paid) {
    return (
      <div className="form-page">
        <h2>支付成功！</h2>
        <button onClick={() => navigate('/orders')}>返回订单</button>
      </div>
    );
  }

  return (
    <div className="form-page">
      <h2>模拟支付</h2>
      <p>订单号：{orderId}</p>
      {error && <p className="error">{error}</p>}
      <button className="primary" onClick={handlePay}>确认支付</button>
      <button onClick={() => navigate('/orders')}>返回</button>
    </div>
  );
}
