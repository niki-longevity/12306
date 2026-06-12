import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { payOrder } from '../api';

const PAY_METHODS = [
  { key: 'alipay', name: '支付宝', icon: '💳', color: '#1677ff' },
  { key: 'wechat', name: '微信支付', icon: '💚', color: '#07c160' },
  { key: 'unionpay', name: '银联', icon: '🏦', color: '#d32f2f' },
];

export default function Pay() {
  const { orderId } = useParams();
  const navigate = useNavigate();
  const [method, setMethod] = useState('alipay');
  const [status, setStatus] = useState('idle'); // idle | processing | success | error
  const [error, setError] = useState('');
  const [paymentNo, setPaymentNo] = useState('');

  const handlePay = async () => {
    setStatus('processing');
    setError('');
    try {
      const res = await payOrder(orderId);
      if (res.data.code === 1) {
        setPaymentNo(res.data.data?.paymentNo || '');
        setStatus('success');
      } else {
        setError(res.data.msg || '支付失败');
        setStatus('error');
      }
    } catch {
      setError('网络异常，请重试');
      setStatus('error');
    }
  };

  if (status === 'success') {
    return (
      <div className="form-page" style={{ textAlign: 'center' }}>
        <div style={{ fontSize: 48, marginBottom: 12 }}>✅</div>
        <h2 style={{ color: '#07c160' }}>支付成功！</h2>
        {paymentNo && (
          <p style={{ color: '#666', fontSize: 13 }}>
            交易号：{paymentNo}
          </p>
        )}
        <div style={{ display: 'flex', gap: 8, justifyContent: 'center', marginTop: 20 }}>
          <button onClick={() => navigate('/orders')}>查看订单</button>
          <button onClick={() => navigate('/tickets')}>继续购票</button>
        </div>
      </div>
    );
  }

  const isProcessing = status === 'processing';

  return (
    <div className="form-page" style={{ maxWidth: 440 }}>
      <h2>确认支付</h2>

      <div style={{ background: '#f8f9fa', borderRadius: 8, padding: 16, marginBottom: 20 }}>
        <div style={{ fontSize: 13, color: '#666', marginBottom: 4 }}>订单号</div>
        <div style={{ fontSize: 16, fontWeight: 600, wordBreak: 'break-all' }}>{orderId}</div>
      </div>

      <div style={{ marginBottom: 24 }}>
        <label style={{ fontSize: 14, fontWeight: 600, display: 'block', marginBottom: 10 }}>支付方式</label>
        {PAY_METHODS.map(m => (
          <div key={m.key} onClick={() => !isProcessing && setMethod(m.key)}
            style={{
              padding: '12px 14px', borderRadius: 6, border: '2px solid',
              borderColor: method === m.key ? m.color : '#ddd',
              background: method === m.key ? `${m.color}08` : '#fff',
              cursor: isProcessing ? 'default' : 'pointer',
              display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8,
              transition: '0.15s', opacity: isProcessing ? 0.6 : 1,
            }}>
            <span style={{ fontSize: 22 }}>{m.icon}</span>
            <span style={{ fontWeight: 500 }}>{m.name}</span>
            {method === m.key && <span style={{ marginLeft: 'auto', color: m.color }}>✓</span>}
          </div>
        ))}
      </div>

      {error && <p className="error" style={{ marginBottom: 12 }}>{error}</p>}

      {isProcessing && (
        <div style={{ textAlign: 'center', padding: 16, color: '#666', marginBottom: 12 }}>
          <div className="spinner" style={{
            width: 32, height: 32, border: '3px solid #eee', borderTop: '3px solid #1a73e8',
            borderRadius: '50%', animation: 'spin 0.8s linear infinite', margin: '0 auto 8px',
          }} />
          正在连接支付网关...
        </div>
      )}

      <button className="primary" onClick={handlePay} disabled={isProcessing}
        style={{ width: '100%', padding: 14, fontSize: 16, fontWeight: 600 }}>
        {isProcessing ? '支付处理中...' : '确认支付'}
      </button>
      <button onClick={() => navigate('/orders')} disabled={isProcessing}
        style={{ width: '100%', marginTop: 8, padding: 12, fontSize: 14, background: 'none', border: '1px solid #ddd', borderRadius: 6, cursor: 'pointer' }}>
        返回
      </button>
    </div>
  );
}
