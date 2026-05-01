# Frontend React Implementation Plan

> **Goal:** Build a React SPA (port 3000) for 12306Pro: login, register, query, buy, orders, pay.

**Architecture:** Vite + React + React Router + Axios, calling Gateway (8080) which routes to services.

**Tech Stack:** React 18, Vite 5, React Router 7, Axios

---

### Task 1: Scaffold Vite + React project

- [ ] `npm create vite@latest frontend -- --template react`
- [ ] `cd frontend && npm install && npm install react-router-dom axios`
- [ ] Add proxy config to `vite.config.js` (proxy /user, /ticket, /order → http://localhost:8080)
- [ ] Commit

### Task 2: Create api.js (API layer)

- [ ] Base URL config, unified fetch/axios
- [ ] Functions: login, register, queryTickets, buyTicket, getOrders, payOrder, cancelOrder
- [ ] Token management (localStorage)

### Task 3: Create App.jsx + Router

- [ ] BrowserRouter with routes: /login, /register, /tickets, /buy/:trainCode, /orders, /pay/:orderId
- [ ] Layout with header + navigation

### Task 4: Create pages

- [ ] Login.jsx — username/phone + password form
- [ ] Register.jsx — registration form
- [ ] TicketList.jsx — date/start/end inputs + train list table
- [ ] Buy.jsx — confirm seat type + passenger form
- [ ] Orders.jsx — order list with pay/cancel buttons
- [ ] Pay.jsx — simulate payment

### Task 5: Integration test

- [ ] Start all services + frontend
- [ ] Full flow: register → login → query → buy → check order → pay
