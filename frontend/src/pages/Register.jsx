import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { register } from '../api';
import { useToast } from '../components/Toast';

const PHONE_RE = /^1[3-9]\d{9}$/;
const USERNAME_RE = /^[a-zA-Z0-9_]{4,20}$/;

function checkIdCard(idCard) {
  if (!/^\d{17}[\dXx]$/.test(idCard)) return false;
  const weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2];
  const checkMap = ['1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'];
  const sum = idCard.substring(0, 17).split('').reduce((s, d, i) => s + parseInt(d) * weights[i], 0);
  return checkMap[sum % 11] === idCard[17].toUpperCase();
}

function passwordStrength(pwd) {
  if (!pwd || pwd.length < 6) return { level: 0, label: '', color: '#e0e0e0', width: 0 };
  let score = 0;
  if (pwd.length >= 8) score++;
  if (/[a-zA-Z]/.test(pwd) && /\d/.test(pwd)) score++;
  if (/[^a-zA-Z0-9]/.test(pwd)) score++;
  const levels = [
    { level: 1, label: '弱', color: '#d32f2f', width: 33 },
    { level: 2, label: '中', color: '#e8870a', width: 66 },
    { level: 3, label: '强', color: '#2e7d32', width: 100 },
  ];
  return levels[Math.min(score, levels.length - 1)];
}

export default function Register() {
  const [form, setForm] = useState({ username: '', password: '', phone: '', realName: '', idCard: '' });
  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const toast = useToast();

  const pwdStrength = passwordStrength(form.password);

  const validators = {
    username: (v) => !v ? '请输入用户名' : !USERNAME_RE.test(v) ? '4-20位字母/数字/下划线' : '',
    phone: (v) => !v ? '请输入手机号' : !PHONE_RE.test(v) ? '手机号格式不正确' : '',
    password: (v) => !v ? '请输入密码' : v.length < 6 ? '密码至少6位' : '',
    realName: (v) => !v ? '请输入真实姓名' : v.length < 2 ? '姓名至少2个字符' : '',
    idCard: (v) => !v ? '请输入身份证号' : !checkIdCard(v) ? '身份证号格式不正确' : '',
  };

  const validateField = (field) => {
    setErrors(prev => ({ ...prev, [field]: validators[field](form[field]) }));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setServerError('');

    const newErrors = {};
    Object.keys(validators).forEach(k => { newErrors[k] = validators[k](form[k]); });
    setErrors(newErrors);
    if (Object.values(newErrors).some(e => e)) return;

    setLoading(true);
    try {
      const res = await register(form);
      if (res.data.code === 1) {
        toast.show('注册成功！即将跳转登录');
        setTimeout(() => navigate('/login'), 1000);
      } else {
        setServerError(res.data.msg || '注册失败');
      }
    } catch {
      setServerError('网络错误，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  const set = (k) => (e) => {
    setForm({ ...form, [k]: e.target.value });
    setErrors(prev => ({ ...prev, [k]: '' }));
  };

  const inputStyle = (field) => ({
    width: '100%', padding: '10px 12px',
    border: `1px solid ${errors[field] ? '#d32f2f' : '#ddd'}`,
    borderRadius: 6, fontSize: 14, outline: 'none',
  });

  return (
    <div className="form-page">
      <div style={{ textAlign: 'center', marginBottom: 24 }}>
        <div style={{ fontSize: 26, fontWeight: 700, color: '#1a73e8', marginBottom: 4 }}>创建账号</div>
        <div style={{ fontSize: 13, color: '#999' }}>加入 12306Pro，轻松购票</div>
      </div>

      {serverError && (
        <div style={{ background: '#fce4ec', color: '#d32f2f', padding: '10px 12px', borderRadius: 6, marginBottom: 12, fontSize: 13 }}>
          {serverError}
        </div>
      )}

      <form onSubmit={handleSubmit}>
        <div style={{ marginBottom: 12 }}>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>用户名</label>
          <input placeholder="4-20位字母/数字/下划线" value={form.username} onChange={set('username')} onBlur={() => validateField('username')} style={inputStyle('username')} />
          {errors.username && <div style={{ color: '#d32f2f', fontSize: 12, marginTop: 4 }}>{errors.username}</div>}
        </div>

        <div style={{ marginBottom: 12 }}>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>手机号</label>
          <input placeholder="请输入11位手机号" value={form.phone} onChange={set('phone')} onBlur={() => validateField('phone')} style={inputStyle('phone')} />
          {errors.phone && <div style={{ color: '#d32f2f', fontSize: 12, marginTop: 4 }}>{errors.phone}</div>}
        </div>

        <div style={{ marginBottom: 12 }}>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>密码</label>
          <input type="password" placeholder="至少6位密码" value={form.password} onChange={set('password')} onBlur={() => validateField('password')} style={inputStyle('password')} />
          {form.password && (
            <div style={{ marginTop: 6 }}>
              <div style={{ height: 4, background: '#e0e0e0', borderRadius: 2 }}>
                <div style={{ height: 4, width: `${pwdStrength.width}%`, background: pwdStrength.color, borderRadius: 2, transition: 'width .3s' }} />
              </div>
              <div style={{ fontSize: 11, color: pwdStrength.color, marginTop: 2 }}>密码强度：{pwdStrength.label}</div>
            </div>
          )}
          {errors.password && <div style={{ color: '#d32f2f', fontSize: 12, marginTop: 4 }}>{errors.password}</div>}
        </div>

        <div style={{ marginBottom: 12 }}>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>真实姓名</label>
          <input placeholder="请输入真实姓名" value={form.realName} onChange={set('realName')} onBlur={() => validateField('realName')} style={inputStyle('realName')} />
          {errors.realName && <div style={{ color: '#d32f2f', fontSize: 12, marginTop: 4 }}>{errors.realName}</div>}
        </div>

        <div style={{ marginBottom: 16 }}>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>身份证号</label>
          <input placeholder="请输入18位身份证号" value={form.idCard} onChange={set('idCard')} onBlur={() => validateField('idCard')} style={inputStyle('idCard')} />
          {errors.idCard && <div style={{ color: '#d32f2f', fontSize: 12, marginTop: 4 }}>{errors.idCard}</div>}
        </div>

        <button type="submit" disabled={loading}
          style={{ width: '100%', padding: 12, fontSize: 15, fontWeight: 600, opacity: loading ? 0.6 : 1 }}>
          {loading ? '注册中...' : '注册'}
        </button>

        <div style={{ textAlign: 'center', marginTop: 16, fontSize: 13, color: '#666' }}>
          已有账号？<Link to="/login" style={{ color: '#1a73e8' }}>去登录</Link>
        </div>
      </form>
    </div>
  );
}
