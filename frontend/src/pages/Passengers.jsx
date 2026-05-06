import { useState, useEffect } from 'react';
import { getPassengers, addPassenger, updatePassenger, deletePassenger } from '../api';
import Modal from '../components/Modal';
import Skeleton from '../components/Skeleton';
import { useToast } from '../components/Toast';

const TYPE_LABELS = { ADULT: '成人', STUDENT: '学生', CHILD: '儿童' };
const TYPE_COLORS = { ADULT: '#1a73e8', STUDENT: '#d32f2f', CHILD: '#2e7d32' };
const TYPE_BG = { ADULT: '#e8f0fe', STUDENT: '#fce4ec', CHILD: '#e8f5e9' };
const ID_CARD_RE = /^\d{17}[\dXx]$/;

function checkIdCard(idCard) {
  if (!ID_CARD_RE.test(idCard)) return false;
  const weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2];
  const checkMap = ['1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'];
  const sum = idCard.substring(0, 17).split('').reduce((s, d, i) => s + parseInt(d) * weights[i], 0);
  return checkMap[sum % 11] === idCard[17].toUpperCase();
}

function maskIdCard(idCard) {
  if (!idCard || idCard.length < 8) return idCard;
  return idCard.substring(0, 4) + '**********' + idCard.substring(14);
}

const emptyForm = { realName: '', idCard: '', passengerType: 'ADULT' };

export default function Passengers() {
  const [passengers, setPassengers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [errors, setErrors] = useState({});
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  useEffect(() => { load(); }, []);

  const load = async () => {
    setLoading(true);
    try {
      const res = await getPassengers();
      if (res.data.code === 1) setPassengers(res.data.data || []);
    } catch { toast.show('加载乘车人失败', 'error'); }
    setLoading(false);
  };

  const openAdd = () => {
    setEditing(null);
    setForm(emptyForm);
    setErrors({});
    setModalOpen(true);
  };

  const openEdit = (p) => {
    setEditing(p);
    setForm({ realName: p.realName, idCard: p.idCard, passengerType: p.passengerType });
    setErrors({});
    setModalOpen(true);
  };

  const validate = () => {
    const errs = {};
    if (!form.realName || form.realName.length < 2) errs.realName = '姓名至少2个字符';
    if (!form.idCard) errs.idCard = '请输入身份证号';
    else if (!checkIdCard(form.idCard)) errs.idCard = '身份证号格式不正确';
    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleSave = async () => {
    if (!validate()) return;
    setSaving(true);
    try {
      if (editing) {
        const res = await updatePassenger(editing.id, form);
        if (res.data.code === 1) {
          toast.show('修改成功');
          setModalOpen(false);
          load();
        } else {
          toast.show(res.data.msg || '修改失败', 'error');
        }
      } else {
        const res = await addPassenger(form);
        if (res.data.code === 1) {
          toast.show('添加成功');
          setModalOpen(false);
          load();
        } else {
          toast.show(res.data.msg || '添加失败', 'error');
        }
      }
    } catch {
      toast.show('网络错误', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (p) => {
    if (!confirm(`确定删除乘车人"${p.realName}"吗？`)) return;
    try {
      const res = await deletePassenger(p.id);
      if (res.data.code === 1) {
        toast.show('删除成功');
        load();
      } else {
        toast.show(res.data.msg || '删除失败', 'error');
      }
    } catch { toast.show('网络错误', 'error'); }
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2>常用乘车人</h2>
        <button onClick={openAdd}
          style={{ background: '#1a73e8', color: '#fff', border: 'none', padding: '8px 20px', borderRadius: 6, cursor: 'pointer', fontSize: 14 }}>
          + 添加乘车人
        </button>
      </div>

      {loading ? (
        <Skeleton rows={2} />
      ) : passengers.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 60, color: '#999' }}>
          <div style={{ fontSize: 48, marginBottom: 12 }}>👤</div>
          <div style={{ fontSize: 15, marginBottom: 8 }}>还没有添加乘车人</div>
          <div style={{ fontSize: 13, marginBottom: 16 }}>添加常用乘车人，购票更快捷</div>
          <button onClick={openAdd}
            style={{ background: '#1a73e8', color: '#fff', border: 'none', padding: '8px 24px', borderRadius: 6, cursor: 'pointer', fontSize: 14 }}>
            + 添加第一位乘车人
          </button>
        </div>
      ) : (
        <>
          <div style={{ background: '#fff', borderRadius: 8, overflow: 'hidden', boxShadow: '0 1px 3px rgba(0,0,0,.08)' }}>
            {passengers.map(p => (
              <div key={p.id} style={{ display: 'flex', alignItems: 'center', padding: '14px 20px', borderBottom: '1px solid #f0f0f0' }}>
                <div style={{ flex: 1 }}>
                  <strong style={{ fontSize: 15 }}>{p.realName}</strong>
                  <span style={{
                    marginLeft: 8, padding: '2px 8px', borderRadius: 3, fontSize: 11, fontWeight: 600,
                    background: TYPE_BG[p.passengerType] || '#f0f0f0',
                    color: TYPE_COLORS[p.passengerType] || '#999',
                  }}>
                    {TYPE_LABELS[p.passengerType] || p.passengerType}
                  </span>
                </div>
                <div style={{ flex: 2, fontSize: 13, color: '#666' }}>{maskIdCard(p.idCard)}</div>
                <div style={{ display: 'flex', gap: 12 }}>
                  <span onClick={() => openEdit(p)} style={{ color: '#1a73e8', cursor: 'pointer', fontSize: 13 }}>编辑</span>
                  <span onClick={() => handleDelete(p)} style={{ color: '#d32f2f', cursor: 'pointer', fontSize: 13 }}>删除</span>
                </div>
              </div>
            ))}
          </div>
          <div style={{ textAlign: 'center', color: '#999', fontSize: 12, marginTop: 12 }}>
            共 {passengers.length} 位乘车人，最多可添加 10 位
          </div>
        </>
      )}

      <Modal
        title={editing ? '编辑乘车人' : '添加乘车人'}
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        footer={
          <>
            <button onClick={() => setModalOpen(false)}
              style={{ background: '#f0f0f0', color: '#333', border: 'none', padding: '8px 20px', borderRadius: 6, cursor: 'pointer', fontSize: 14 }}>
              取消
            </button>
            <button onClick={handleSave} disabled={saving}
              style={{ background: '#1a73e8', color: '#fff', border: 'none', padding: '8px 20px', borderRadius: 6, cursor: 'pointer', fontSize: 14, opacity: saving ? 0.6 : 1 }}>
              {saving ? '保存中...' : '保存'}
            </button>
          </>
        }
      >
        <div style={{ marginBottom: 14 }}>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>姓名</label>
          <input value={form.realName} onChange={e => setForm({ ...form, realName: e.target.value })}
            style={{ width: '100%', padding: '10px 12px', border: `1px solid ${errors.realName ? '#d32f2f' : '#ddd'}`, borderRadius: 6, fontSize: 14, outline: 'none' }} />
          {errors.realName && <div style={{ color: '#d32f2f', fontSize: 12, marginTop: 4 }}>{errors.realName}</div>}
        </div>
        <div style={{ marginBottom: 14 }}>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>身份证号</label>
          <input value={form.idCard} onChange={e => setForm({ ...form, idCard: e.target.value })}
            style={{ width: '100%', padding: '10px 12px', border: `1px solid ${errors.idCard ? '#d32f2f' : '#ddd'}`, borderRadius: 6, fontSize: 14, outline: 'none' }} />
          {errors.idCard && <div style={{ color: '#d32f2f', fontSize: 12, marginTop: 4 }}>{errors.idCard}</div>}
        </div>
        <div style={{ marginBottom: 4 }}>
          <label style={{ fontSize: 13, color: '#666', display: 'block', marginBottom: 4 }}>乘客类型</label>
          <select value={form.passengerType} onChange={e => setForm({ ...form, passengerType: e.target.value })}
            style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 6, fontSize: 14, outline: 'none', background: '#fff' }}>
            <option value="ADULT">成人</option>
            <option value="STUDENT">学生</option>
            <option value="CHILD">儿童</option>
          </select>
        </div>
      </Modal>
    </div>
  );
}
