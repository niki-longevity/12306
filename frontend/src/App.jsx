import { useState, useEffect } from 'react';
import { BrowserRouter, Routes, Route, Link, Navigate, useNavigate, useLocation } from 'react-router-dom';
import Login from './pages/Login';
import Register from './pages/Register';
import TicketList from './pages/TicketList';
import Buy from './pages/Buy';
import Orders from './pages/Orders';
import Pay from './pages/Pay';
import Passengers from './pages/Passengers';
import Profile from './pages/Profile';
import { ToastProvider } from './components/Toast';
import { getProfile } from './api';
import './App.css';

function Header() {
  const [showMenu, setShowMenu] = useState(false);
  const [userName, setUserName] = useState('');
  const token = localStorage.getItem('token');

  useEffect(() => {
    if (!token) return;
    try {
      const payload = JSON.parse(atob(token.split('.')[1]));
      setUserName(payload.username || '');
    } catch {}
    getProfile().then(res => {
      if (res.data.code === 1 && res.data.data?.realName) {
        setUserName(res.data.data.realName);
      }
    }).catch(() => {});
  }, [token]);

  const initial = userName ? userName.charAt(0).toUpperCase() : '?';

  return (
    <header>
      <Link to="/tickets" className="logo">12306Pro</Link>
      <nav>
        <Link to="/tickets">查票</Link>
        <Link to="/orders">订单</Link>
        {token && <Link to="/passengers">乘车人</Link>}
        {token ? (
          <div className="user-menu" onClick={() => setShowMenu(!showMenu)}>
            <div className="avatar">{initial}</div>
            <span className="user-name">{userName || '用户'}</span>
            <span className="arrow">▼</span>
            {showMenu && (
              <div className="dropdown">
                <Link to="/profile" onClick={() => setShowMenu(false)}>个人资料</Link>
                <div onClick={() => { window._logout(); setShowMenu(false); }}>退出登录</div>
              </div>
            )}
          </div>
        ) : (
          <Link to="/login">登录</Link>
        )}
      </nav>
    </header>
  );
}

function AppRoutes() {
  const token = localStorage.getItem('token');
  const location = useLocation();

  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      <Route path="/tickets" element={<TicketList />} />
      <Route path="/buy" element={token ? <Buy /> : <Navigate to="/login" state={{ from: '/buy' }} />} />
      <Route path="/orders" element={token ? <Orders /> : <Navigate to="/login" state={{ from: '/orders' }} />} />
      <Route path="/pay/:orderId" element={token ? <Pay /> : <Navigate to="/login" state={{ from: location.pathname }} />} />
      <Route path="/passengers" element={token ? <Passengers /> : <Navigate to="/login" state={{ from: '/passengers' }} />} />
      <Route path="/profile" element={token ? <Profile /> : <Navigate to="/login" state={{ from: '/profile' }} />} />
      <Route path="*" element={<Navigate to="/tickets" />} />
    </Routes>
  );
}

function App() {
  const [token, setToken] = useState(localStorage.getItem('token'));
  window._setToken = (t) => { localStorage.setItem('token', t); setToken(t); };
  window._logout = () => { localStorage.removeItem('token'); setToken(null); };

  return (
    <BrowserRouter>
      <ToastProvider>
        <div className="app">
          <Header />
          <main>
            <AppRoutes />
          </main>
        </div>
      </ToastProvider>
    </BrowserRouter>
  );
}

export default App;
