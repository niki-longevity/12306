import { useState, useEffect } from 'react';
import { getStationCities, searchStations } from '../api';

const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');

export default function CityPanel({ onSelectStation, onClose }) {
  const [indexData, setIndexData] = useState({});
  const [activeLetter, setActiveLetter] = useState('');
  const [selectedCity, setSelectedCity] = useState(null);
  const [cityStations, setCityStations] = useState([]);

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

  const handleCityClick = async (city) => {
    setSelectedCity(city);
    try {
      const res = await searchStations(city);
      if (res.data.code === 1) {
        setCityStations(res.data.data || []);
      }
    } catch { setCityStations([]); }
  };

  const handleStationClick = (stationName) => {
    onSelectStation(stationName);
    onClose();
  };

  const handleBack = () => {
    setSelectedCity(null);
    setCityStations([]);
  };

  // Station selection view (after city is picked)
  if (selectedCity) {
    return (
      <div style={{
        position: 'fixed', bottom: 0, left: 0, right: 0, top: 0, zIndex: 200,
        background: '#fff', display: 'flex', flexDirection: 'column'
      }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                       padding: '12px 16px', borderBottom: '1px solid #eee' }}>
          <button onClick={handleBack} style={{ background: 'none', border: 'none', fontSize: 16,
            cursor: 'pointer', color: '#1a73e8', padding: 0 }}>&larr; 返回</button>
          <h3 style={{ margin: 0, fontSize: 16 }}>{selectedCity}</h3>
          <button onClick={onClose} style={{ background: 'none', border: 'none', fontSize: 24,
            cursor: 'pointer', color: '#999', padding: '4px 8px' }}>&times;</button>
        </div>
        <div style={{ flex: 1, overflowY: 'auto', padding: '8px 0' }}>
          {cityStations.length === 0 ? (
            <p style={{ padding: 16, color: '#999' }}>暂无车站信息</p>
          ) : (
            cityStations.map((s, i) => (
              <div key={i} onClick={() => handleStationClick(s.stationName)}
                style={{
                  padding: '12px 16px', borderBottom: '1px solid #f5f5f5',
                  cursor: 'pointer', display: 'flex', justifyContent: 'space-between'
                }}>
                <span style={{ fontSize: 15, fontWeight: 500 }}>{s.stationName}</span>
                <span style={{ fontSize: 12, color: '#999' }}>{s.pinyinAbbr}</span>
              </div>
            ))
          )}
        </div>
      </div>
    );
  }

  // City list view (default)
  return (
    <div style={{
      position: 'fixed', bottom: 0, left: 0, right: 0, top: 0, zIndex: 200,
      background: '#fff', display: 'flex', flexDirection: 'column'
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                     padding: '12px 16px', borderBottom: '1px solid #eee' }}>
        <h3 style={{ margin: 0 }}>选择城市</h3>
        <button onClick={onClose} style={{ background: 'none', border: 'none', fontSize: 24,
          cursor: 'pointer', color: '#999', padding: '4px 8px' }}>&times;</button>
      </div>

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

      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
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

        <div style={{ flex: 1, overflowY: 'auto', padding: '8px 0' }}>
          {Object.keys(indexData).sort().map(letter => (
            activeLetter === letter ? (
              <div key={letter}>
                <div style={{ padding: '4px 16px', background: '#f5f5f5', fontSize: 13,
                              fontWeight: 600, color: '#666' }}>{letter}</div>
                {indexData[letter].map((city, i) => (
                  <div key={i} onClick={() => handleCityClick(city)}
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
