import { useState } from 'react';
import { useNavigate, Link, useLocation } from 'react-router-dom';
import { login } from '../api';
import { useToast } from '../components/Toast';

const PHONE_RE = /^1[3-9]\d{9}$/;
const USERNAME_RE = /^[a-zA-Z0-9_]{4,20}$/;

export default function Login() {
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const [remember, setRemember] = useState(false);
  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const toast = useToast();

  const validate = (value) => {
    if (!value) return '请输入手机号或用户名';
    if (/^\d/.test(value) && !PHONE_RE.test(value)) return '手机号格式不正确';
    if (!/^\d/.test(value) && !USERNAME_RE.test(value)) return '用户名格式不正确（4-20位字母/数字/下划线）';
    return '';
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setServerError('');

    const loginErr = validate(loginId);
    const pwdErr = !password ? '请输入密码' : '';
    setErrors({ loginId: loginErr, password: pwdErr });
    if (loginErr || pwdErr) return;

    setLoading(true);
    try {
      const res = await login({ loginId, password });
      if (res.data.code === 1) {
        const token = res.data.data.token;
        if (remember) {
          localStorage.setItem('rememberedLoginId', loginId);
        } else {
          localStorage.removeItem('rememberedLoginId');
        }
        localStorage.setItem('token', token);
        window._setToken(token);
        toast.show('登录成功');
        const from = location.state?.from || '/tickets';
        navigate(from);
      } else {
        setServerError(res.data.msg || '登录失败');
      }
    } catch {
      setServerError('网络错误，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  // 初始化时恢复记住的登录名
  useState(() => {
    const remembered = localStorage.getItem('rememberedLoginId');
    if (remembered) {
      setLoginId(remembered);
      setRemember(true);
    }
  }, []);

  return (
    <div className="form-page">
      <div style={{ textAlign: 'center', marginBottom: 24 }}>
        <div style={{ fontSize: 26, fontWeight: 700, color: '#1a73e8', marginBottom: 4 }}>12306Pro</div>
        <div style={{ fontSize: 13, color: '#999' }}>火车票预订系统</div>
      </div>

      {serverError && (
        <div style={{ background: '#fce4ec', color: '#d32f2f', padding: '10px 12px', borderRadius: 6, marginBottom: 12, fontSize: 13 }}>
          {serverError}
        </div>
      )}

      <form onSubmit={handleSubmit}>
        <div style={{ marginBottom: 14 }}>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>手机号 / 用户名</label>
          <input
            placeholder="请输入手机号或用户名"
            value={loginId}
            onChange={e => { setLoginId(e.target.value); setErrors(prev => ({ ...prev, loginId: '' })); }}
            onBlur={e => setErrors(prev => ({ ...prev, loginId: validate(e.target.value) }))}
            style={{ width: '100%', padding: '10px 12px', border: `1px solid ${errors.loginId ? '#d32f2f' : '#ddd'}`, borderRadius: 6, fontSize: 14, outline: 'none' }}
          />
          {errors.loginId && <div style={{ color: '#d32f2f', fontSize: 12, marginTop: 4 }}>{errors.loginId}</div>}
        </div>

        <div style={{ marginBottom: 14 }}>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>密码</label>
          <div style={{ display: 'flex', alignItems: 'center', position: 'relative' }}>
            <input
              type={showPwd ? 'text' : 'password'}
              placeholder="请输入密码"
              value={password}
              onChange={e => { setPassword(e.target.value); setErrors(prev => ({ ...prev, password: '' })); }}
              onBlur={e => setErrors(prev => ({ ...prev, password: e.target.value ? '' : '请输入密码' }))}
              style={{ width: '100%', padding: '10px 40px 10px 12px', border: `1px solid ${errors.password ? '#d32f2f' : '#ddd'}`, borderRadius: 6, fontSize: 14, outline: 'none' }}
            />
            <span onClick={() => setShowPwd(!showPwd)}
              style={{ position: 'absolute', right: 12, cursor: 'pointer', fontSize: 13, color: '#999', userSelect: 'none' }}>
              {showPwd ? '隐藏' : '显示'}
            </span>
          </div>
          {errors.password && <div style={{ color: '#d32f2f', fontSize: 12, marginTop: 4 }}>{errors.password}</div>}
        </div>

        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20, fontSize: 13 }}>
          <label style={{ color: '#666', display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>
            <input type="checkbox" checked={remember} onChange={e => setRemember(e.target.checked)} />
            记住我
          </label>
        </div>

        <button type="submit" disabled={loading}
          style={{ width: '100%', padding: 12, fontSize: 15, fontWeight: 600, opacity: loading ? 0.6 : 1 }}>
          {loading ? '登录中...' : '登录'}
        </button>

        <div style={{ textAlign: 'center', marginTop: 16, fontSize: 13, color: '#666' }}>
          没有账号？<Link to="/register" style={{ color: '#1a73e8' }}>立即注册</Link>
        </div>
      </form>
    </div>
  );
}
