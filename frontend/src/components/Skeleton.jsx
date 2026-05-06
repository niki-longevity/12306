export default function Skeleton({ rows = 3 }) {
  return (
    <div style={{ padding: '16px 0' }}>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} style={{
          display: 'flex', gap: 12, marginBottom: 12,
          padding: '12px 16px', background: '#fff', borderRadius: 8
        }}>
          <div style={{ flex: 2, height: 14, background: '#eee', borderRadius: 4 }} />
          <div style={{ flex: 1, height: 14, background: '#eee', borderRadius: 4 }} />
          <div style={{ flex: 1, height: 14, background: '#eee', borderRadius: 4 }} />
          <div style={{ width: 60, height: 14, background: '#eee', borderRadius: 4 }} />
        </div>
      ))}
    </div>
  );
}
