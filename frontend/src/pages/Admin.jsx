import { useState, useEffect } from 'react';
import api from '../api';

export default function Admin() {
  const [tab, setTab] = useState('trains');
  const [trains, setTrains] = useState([]);
  const [stations, setStations] = useState([]);
  const [stops, setStops] = useState({});
  const [expanded, setExpanded] = useState(null);
  const [stats, setStats] = useState(null);

  useEffect(() => {
    api.get('/api/admin/trains').then(r => { if (r.data.code === 1) setTrains(r.data.data); });
    api.get('/api/admin/stations').then(r => { if (r.data.code === 1) setStations(r.data.data); });
    api.get('/api/admin/stats').then(r => { if (r.data.code === 1) setStats(r.data.data); });
  }, []);

  const loadStops = async (code) => {
    if (expanded === code) { setExpanded(null); return; }
    setExpanded(code);
    if (!stops[code]) {
      const r = await api.get(`/api/admin/trains/${code}/stops`);
      if (r.data.code === 1) setStops(prev => ({ ...prev, [code]: r.data.data }));
    }
  };

  const tabs = [
    { key: 'trains', label: `车次模板 (${trains.length})` },
    { key: 'stations', label: `站点字典 (${stations.length})` },
  ];

  return (
    <div>
      <h2>管理后台</h2>

      {stats && (
        <div style={{ display: 'flex', gap: 16, marginBottom: 20, flexWrap: 'wrap' }}>
          {[
            { label: '车次', value: stats.trainCount, color: '#1a73e8' },
            { label: '站点', value: stats.stationCount, color: '#07c160' },
            { label: '经停', value: stats.stopoverCount, color: '#e8870a' },
          ].map(s => (
            <div key={s.label} style={{
              flex: 1, minWidth: 140, background: '#f8f9fa', borderRadius: 8, padding: 16,
              borderLeft: `4px solid ${s.color}`, textAlign: 'center',
            }}>
              <div style={{ fontSize: 28, fontWeight: 700, color: s.color }}>{s.value}</div>
              <div style={{ fontSize: 13, color: '#666' }}>{s.label}</div>
            </div>
          ))}
        </div>
      )}

      <div style={{ display: 'flex', gap: 0, marginBottom: 16, borderBottom: '2px solid #eee' }}>
        {tabs.map(t => (
          <div key={t.key} onClick={() => setTab(t.key)} style={{
            padding: '10px 20px', cursor: 'pointer', fontWeight: tab === t.key ? 600 : 400,
            color: tab === t.key ? '#1a73e8' : '#666',
            borderBottom: tab === t.key ? '2px solid #1a73e8' : '2px solid transparent',
            marginBottom: -2, transition: '0.15s',
          }}>{t.label}</div>
        ))}
      </div>

      {tab === 'trains' && (
        <table>
          <thead><tr><th>车次</th><th>线路</th><th>商务</th><th>一等</th><th>二等</th><th>经停</th></tr></thead>
          <tbody>
            {trains.slice(0, 100).map(t => (
              <>
                <tr key={t.trainCode} onClick={() => loadStops(t.trainCode)}
                  style={{ cursor: 'pointer' }}>
                  <td style={{ fontWeight: 600, color: '#1a73e8' }}>{t.trainCode}</td>
                  <td>{t.lineCode || '-'}</td>
                  <td>{t.businessCarriage}</td>
                  <td>{t.firstClassCarriage}</td>
                  <td>{t.secondClassCarriage}</td>
                  <td style={{ color: '#999', fontSize: 12 }}>
                    {stops[t.trainCode] ? `${stops[t.trainCode].length}站` : '点击查看'}
                  </td>
                </tr>
                {expanded === t.trainCode && stops[t.trainCode] && (
                  <tr key={`${t.trainCode}-stops`}>
                    <td colSpan={6} style={{ padding: '8px 20px', background: '#f8f9fa' }}>
                      <div style={{ fontSize: 13, color: '#666', display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                        {stops[t.trainCode].map((s, i) => (
                          <span key={i}>
                            {s.stationName}
                            <span style={{ color: '#999', marginLeft: 4 }}>
                              {s.outTime || s.inTime || ''}
                            </span>
                          </span>
                        ))}
                      </div>
                    </td>
                  </tr>
                )}
              </>
            ))}
          </tbody>
        </table>
      )}

      {tab === 'stations' && (
        <table>
          <thead><tr><th>站名</th><th>城市</th><th>省份</th><th>拼音</th></tr></thead>
          <tbody>
            {stations.slice(0, 200).map(s => (
              <tr key={s.id}>
                <td style={{ fontWeight: 500 }}>{s.stationName}</td>
                <td>{s.city}</td>
                <td>{s.province}</td>
                <td style={{ color: '#999', fontSize: 12 }}>{s.pinyinAbbr}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {tab === 'trains' && trains.length > 100 && (
        <p style={{ color: '#999', fontSize: 13, textAlign: 'center', marginTop: 12 }}>
          仅显示前 100 条，共 {trains.length} 条
        </p>
      )}
    </div>
  );
}
