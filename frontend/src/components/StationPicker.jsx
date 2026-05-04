import { useState, useRef, useEffect, useCallback } from 'react';
import { searchStations } from '../api';

export default function StationPicker({ value, onChange, placeholder }) {
  const [inputValue, setInputValue] = useState(value || '');
  const [suggestions, setSuggestions] = useState([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const containerRef = useRef(null);
  const debounceRef = useRef(null);

  useEffect(() => {
    setInputValue(value || '');
  }, [value]);

  useEffect(() => {
    const handler = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const doSearch = useCallback(async (kw) => {
    if (!kw || kw.length < 1) { setSuggestions([]); setOpen(false); return; }
    setLoading(true);
    try {
      const res = await searchStations(kw);
      if (res.data.code === 1) {
        setSuggestions(res.data.data || []);
        setOpen(true);
      }
    } catch { setSuggestions([]); }
    setLoading(false);
  }, []);

  const handleInput = (e) => {
    const v = e.target.value;
    setInputValue(v);
    onChange(v);
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => doSearch(v), 200);
  };

  const selectStation = (s) => {
    setInputValue(s.stationName);
    onChange(s.stationName);
    setOpen(false);
    setSuggestions([]);
  };

  return (
    <div ref={containerRef} style={{ position: 'relative', flex: 1, minWidth: 180 }}>
      <input
        placeholder={placeholder || '输入城市名/站名/拼音'}
        value={inputValue}
        onChange={handleInput}
        onFocus={() => { if (suggestions.length > 0) setOpen(true); }}
        style={{ padding: '10px', border: '1px solid #ddd', borderRadius: 6,
                 fontSize: 14, width: '100%' }}
      />
      {loading && <span style={{ position: 'absolute', right: 10, top: 10, color: '#999', fontSize: 12 }}>...</span>}
      {open && suggestions.length > 0 && (
        <ul style={{
          position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 100,
          background: '#fff', border: '1px solid #ddd', borderRadius: 6,
          boxShadow: '0 4px 12px rgba(0,0,0,.1)', listStyle: 'none', margin: '4px 0 0', padding: 0, maxHeight: 260, overflowY: 'auto'
        }}>
          {suggestions.map((s, i) => (
            <li key={i} onClick={() => selectStation(s)}
              style={{ padding: '10px 12px', cursor: 'pointer', borderBottom: '1px solid #f5f5f5',
                       display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontWeight: 500 }}>{s.stationName}</span>
              <span style={{ fontSize: 12, color: '#999' }}>{s.city} · {s.province} {s.pinyinAbbr}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
