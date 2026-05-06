import { useState, useEffect } from 'react';
import { getProfile, updateProfile, changePassword } from '../api';
import Modal from '../components/Modal';
import Skeleton from '../components/Skeleton';
import { useToast } from '../components/Toast';

function maskPhone(phone) {
  if (!phone || phone.length < 7) return phone;
  return phone.substring(0, 3) + '****' + phone.substring(7);
}

function maskIdCard(idCard) {
  if (!idCard || idCard.length < 8) return idCard;
  return idCard.substring(0, 4) + '**********' + idCard.substring(14);
}

export default function Profile() {
  const [profile, setProfile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [phoneModalOpen, setPhoneModalOpen] = useState(false);
  const [pwdModalOpen, setPwdModalOpen] = useState(false);
  const [phone, setPhone] = useState('');
  const [pwdForm, setPwdForm] = useState({ oldPassword: '', newPassword: '' });
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  useEffect(() => { load(); }, []);

  const load = async () => {
    setLoading(true);
    try {
      const res = await getProfile();
      if (res.data.code === 1) setProfile(res.data.data);
    } catch { toast.show('加载个人资料失败', 'error'); }
    setLoading(false);
  };

  const handleUpdatePhone = async () => {
    if (!/^1[3-9]\d{9}$/.test(phone)) {
      toast.show('手机号格式不正确', 'error');
      return;
    }
    setSaving(true);
    try {
      const res = await updateProfile({ phone });
      if (res.data.code === 1) {
        toast.show('手机号修改成功');
        setPhoneModalOpen(false);
        load();
      } else {
        toast.show(res.data.msg || '修改失败', 'error');
      }
    } catch { toast.show('网络错误', 'error'); }
    setSaving(false);
  };

  const handleChangePwd = async () => {
    if (!pwdForm.oldPassword) { toast.show('请输入原密码', 'error'); return; }
    if (!pwdForm.newPassword || pwdForm.newPassword.length < 6) { toast.show('新密码至少6位', 'error'); return; }
    setSaving(true);
    try {
      const res = await changePassword(pwdForm);
      if (res.data.code === 1) {
        toast.show('密码修改成功，请重新登录');
        setPwdModalOpen(false);
        setTimeout(() => { window._logout(); }, 1500);
      } else {
        toast.show(res.data.msg || '修改失败', 'error');
      }
    } catch { toast.show('网络错误', 'error'); }
    setSaving(false);
  };

  if (loading) return <div><h2>个人资料</h2><Skeleton rows={3} /></div>;

  const initial = profile?.realName ? profile.realName.charAt(0) : '?';

  return (
    <div>
      <h2>个人资料</h2>

      <div style={{ background: '#fff', borderRadius: 12, padding: 24, marginTop: 16, boxShadow: '0 1px 3px rgba(0,0,0,.08)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <div style={{
            width: 72, height: 72, borderRadius: '50%', background: '#1a73e8', color: '#fff',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 32, fontWeight: 700, margin: '0 auto 12px'
          }}>
            {initial}
          </div>
          <div style={{ fontWeight: 700, fontSize: 18 }}>{profile?.realName}</div>
          <div style={{ fontSize: 12, color: '#999', marginTop: 4 }}>
            注册时间：{profile?.createTime ? new Date(profile.createTime).toLocaleDateString('zh-CN') : '—'}
          </div>
        </div>

        <div style={{ marginBottom: 20 }}>
          <div style={{ fontSize: 13, fontWeight: 600, color: '#666', marginBottom: 8, textTransform: 'uppercase', letterSpacing: 1 }}>基本信息</div>
          <div style={{ background: '#f8f9fa', padding: '12px 16px', borderRadius: 8, fontSize: 14, lineHeight: 2.2 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span>用户名：<strong>{profile?.username}</strong></span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>手机号：{maskPhone(profile?.phone)}</span>
              <span onClick={() => { setPhone(profile?.phone || ''); setPhoneModalOpen(true); }}
                style={{ color: '#1a73e8', cursor: 'pointer', fontSize: 13 }}>修改</span>
            </div>
            <div>真实姓名：{profile?.realName}</div>
            <div>身份证号：{maskIdCard(profile?.idCard)}</div>
          </div>
        </div>

        <div>
          <div style={{ fontSize: 13, fontWeight: 600, color: '#666', marginBottom: 8, textTransform: 'uppercase', letterSpacing: 1 }}>账号安全</div>
          <div style={{ background: '#f8f9fa', padding: '12px 16px', borderRadius: 8, fontSize: 14 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>登录密码</span>
              <span onClick={() => { setPwdForm({ oldPassword: '', newPassword: '' }); setPwdModalOpen(true); }}
                style={{ color: '#1a73e8', cursor: 'pointer', fontSize: 13 }}>修改</span>
            </div>
          </div>
        </div>
      </div>

      <Modal
        title="修改手机号"
        open={phoneModalOpen}
        onClose={() => setPhoneModalOpen(false)}
        footer={
          <>
            <button onClick={() => setPhoneModalOpen(false)}
              style={{ background: '#f0f0f0', color: '#333', border: 'none', padding: '8px 20px', borderRadius: 6, cursor: 'pointer', fontSize: 14 }}>取消</button>
            <button onClick={handleUpdatePhone} disabled={saving}
              style={{ background: '#1a73e8', color: '#fff', border: 'none', padding: '8px 20px', borderRadius: 6, cursor: 'pointer', fontSize: 14, opacity: saving ? 0.6 : 1 }}>
              {saving ? '保存中...' : '保存'}
            </button>
          </>
        }
      >
        <div>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>新手机号</label>
          <input value={phone} onChange={e => setPhone(e.target.value)}
            style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 6, fontSize: 14, outline: 'none' }}
            placeholder="请输入11位手机号" />
        </div>
      </Modal>

      <Modal
        title="修改密码"
        open={pwdModalOpen}
        onClose={() => setPwdModalOpen(false)}
        footer={
          <>
            <button onClick={() => setPwdModalOpen(false)}
              style={{ background: '#f0f0f0', color: '#333', border: 'none', padding: '8px 20px', borderRadius: 6, cursor: 'pointer', fontSize: 14 }}>取消</button>
            <button onClick={handleChangePwd} disabled={saving}
              style={{ background: '#1a73e8', color: '#fff', border: 'none', padding: '8px 20px', borderRadius: 6, cursor: 'pointer', fontSize: 14, opacity: saving ? 0.6 : 1 }}>
              {saving ? '保存中...' : '保存'}
            </button>
          </>
        }
      >
        <div style={{ marginBottom: 14 }}>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>原密码</label>
          <input type="password" value={pwdForm.oldPassword} onChange={e => setPwdForm({ ...pwdForm, oldPassword: e.target.value })}
            style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 6, fontSize: 14, outline: 'none' }} />
        </div>
        <div>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>新密码</label>
          <input type="password" value={pwdForm.newPassword} onChange={e => setPwdForm({ ...pwdForm, newPassword: e.target.value })}
            style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 6, fontSize: 14, outline: 'none' }}
            placeholder="至少6位" />
        </div>
      </Modal>
    </div>
  );
}
