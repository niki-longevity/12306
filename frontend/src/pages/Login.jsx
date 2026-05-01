import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { login } from '../api';

export default function Login() {
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    try {
      const res = await login({ loginId, password });
      if (res.data.code === 1) {
        localStorage.setItem('token', res.data.data.token);
        navigate('/tickets');
      } else {
        setError(res.data.msg);
      }
    } catch (err) {
      setError('网络错误');
    }
  };

  return (
    <div className="form-page">
      <h2>登录</h2>
      {error && <p className="error">{error}</p>}
      <form onSubmit={handleSubmit}>
        <input placeholder="手机号或用户名" value={loginId} onChange={e => setLoginId(e.target.value)} />
        <input type="password" placeholder="密码" value={password} onChange={e => setPassword(e.target.value)} />
        <button type="submit">登录</button>
      </form>
      <p>没有账号？<Link to="/register">注册</Link></p>
    </div>
  );
}
