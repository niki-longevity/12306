import { BrowserRouter, Routes, Route, Link, Navigate } from 'react-router-dom';
import Login from './pages/Login';
import Register from './pages/Register';
import TicketList from './pages/TicketList';
import Buy from './pages/Buy';
import Orders from './pages/Orders';
import Pay from './pages/Pay';
import './App.css';

function App() {
  const token = localStorage.getItem('token');

  return (
    <BrowserRouter>
      <div className="app">
        <header>
          <Link to="/tickets" className="logo">12306Pro</Link>
          <nav>
            <Link to="/tickets">查票</Link>
            <Link to="/orders">订单</Link>
            {token ? (
              <button onClick={() => { localStorage.removeItem('token'); window.location.href = '/login'; }}>退出</button>
            ) : (
              <Link to="/login">登录</Link>
            )}
          </nav>
        </header>
        <main>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/register" element={<Register />} />
            <Route path="/tickets" element={<TicketList />} />
            <Route path="/buy" element={<Buy />} />
            <Route path="/orders" element={<Orders />} />
            <Route path="/pay/:orderId" element={<Pay />} />
            <Route path="*" element={<Navigate to="/tickets" />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  );
}

export default App;
