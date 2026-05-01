import axios from 'axios';

const api = axios.create({ baseURL: '' });

api.interceptors.request.use(config => {
  const token = localStorage.getItem('token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

export const login = (data) => api.post('/api/user/login', data);
export const register = (data) => api.post('/api/user/add', data);
export const queryTickets = (date, start, end) =>
  api.get('/api/ticket/list', { params: { date, start, end } });
export const buyTicket = (data) => api.put('/api/ticket/buy', data);
export const getOrders = () => api.get('/api/order/list');
export const payOrder = (id) => api.put(`/api/order/${id}/pay`);
export const cancelOrder = (id) => api.put(`/api/order/${id}/cancel`);
export default api;
