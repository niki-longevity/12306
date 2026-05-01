import axios from 'axios';

const api = axios.create({ baseURL: '' });

api.interceptors.request.use(config => {
  const token = localStorage.getItem('token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

export const login = (data) => api.post('/user/login', data);
export const register = (data) => api.post('/user/add', data);

export const queryTickets = (date, start, end) =>
  api.get('/ticket/list', { params: { date, start, end } });

export const buyTicket = (data) => api.put('/ticket/buy', data);

export const getOrders = () => api.get('/order/list');

export const payOrder = (id) => api.put(`/order/${id}/pay`);

export const cancelOrder = (id) => api.put(`/order/${id}/cancel`);

export default api;
