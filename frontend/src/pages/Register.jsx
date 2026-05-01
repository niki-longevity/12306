import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { register } from '../api';

export default function Register() {
  const [form, setForm] = useState({ username: '', password: '', phone: '', realName: '', idCard: '' });
  const [msg, setMsg] = useState('');
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    try {
      const res = await register(form);
      if (res.data.code === 1) { setMsg('注册成功'); setTimeout(() => navigate('/login'), 1000); }
      else setMsg(res.data.msg);
    } catch { setMsg('网络错误'); }
  };

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  return (
    <div className="form-page">
      <h2>注册</h2>
      {msg && <p className="msg">{msg}</p>}
      <form onSubmit={handleSubmit}>
        <input placeholder="用户名" value={form.username} onChange={set('username')} />
        <input type="password" placeholder="密码" value={form.password} onChange={set('password')} />
        <input placeholder="手机号" value={form.phone} onChange={set('phone')} />
        <input placeholder="真实姓名" value={form.realName} onChange={set('realName')} />
        <input placeholder="身份证号" value={form.idCard} onChange={set('idCard')} />
        <button type="submit">注册</button>
      </form>
      <p>已有账号？<Link to="/login">登录</Link></p>
    </div>
  );
}
