import { useState, useEffect } from 'react';
import { getStationCities } from '../api';

const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');

export default function CityPanel({ onSelectStation, onClose }) {
  const [indexData, setIndexData] = useState({});
  const [activeLetter, setActiveLetter] = useState('');

  useEffect(() => {
    (async () => {
      try {
        const res = await getStationCities();
        if (res.data.code === 1) {
          const data = res.data.data || [];
          const map = {};
          data.forEach(d => { map[d.letter] = d.cities || []; });
          setIndexData(map);
          const first = Object.keys(map).sort()[0];
          if (first) setActiveLetter(first);
        }
      } catch { /* ignore */ }
    })();
  }, []);

  const availableLetters = LETTERS.filter(l => indexData[l] && indexData[l].length > 0);

  return (
    <div style={{
      position: 'fixed', bottom: 0, left: 0, right: 0, top: 0, zIndex: 200,
      background: '#fff', display: 'flex', flexDirection: 'column'
    }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                     padding: '12px 16px', borderBottom: '1px solid #eee' }}>
        <h3 style={{ margin: 0 }}>选择城市</h3>
        <button onClick={onClose} style={{ background: 'none', border: 'none', fontSize: 24,
          cursor: 'pointer', color: '#999', padding: '4px 8px' }}>&times;</button>
      </div>

      {/* Quick search */}
      <div style={{ padding: '8px 16px' }}>
        <input placeholder="快速搜索城市"
          style={{ width: '100%', padding: '10px', border: '1px solid #ddd', borderRadius: 6, fontSize: 14 }}
          onChange={(e) => {
            const kw = e.target.value.trim();
            if (!kw) { setActiveLetter(availableLetters[0] || ''); return; }
            const upper = kw.charAt(0).toUpperCase();
            if (indexData[upper]) setActiveLetter(upper);
          }}
        />
      </div>

      {/* Body: letter sidebar + city list */}
      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
        {/* Letter index */}
        <div style={{
          width: 44, background: '#f8f8f8', borderRight: '1px solid #eee',
          display: 'flex', flexDirection: 'column', alignItems: 'center',
          overflowY: 'auto', padding: '4px 0', fontSize: 12
        }}>
          {LETTERS.map(l => {
            const has = !!indexData[l];
            const active = l === activeLetter;
            return (
              <span key={l} onClick={() => has && setActiveLetter(l)}
                style={{
                  padding: '0 0', cursor: has ? 'pointer' : 'default',
                  color: active ? '#fff' : has ? '#1a73e8' : '#ccc',
                  background: active ? '#1a73e8' : 'transparent',
                  borderRadius: 3, width: 24, textAlign: 'center',
                  fontWeight: active ? 600 : 400,
                  lineHeight: '22px'
                }}>
                {l}
              </span>
            );
          })}
        </div>

        {/* City list grouped by letter */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '8px 0' }}>
          {Object.keys(indexData).sort().map(letter => (
            activeLetter === letter ? (
              <div key={letter}>
                <div style={{ padding: '4px 16px', background: '#f5f5f5', fontSize: 13,
                              fontWeight: 600, color: '#666' }}>{letter}</div>
                {indexData[letter].map((city, i) => (
                  <div key={i} onClick={() => { onSelectStation(city); onClose(); }}
                    style={{
                      padding: '12px 16px', borderBottom: '1px solid #f5f5f5',
                      cursor: 'pointer', fontSize: 15, color: '#333'
                    }}>
                    {city}
                  </div>
                ))}
              </div>
            ) : null
          ))}
        </div>
      </div>
    </div>
  );
}
