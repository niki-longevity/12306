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
export const searchStations = (keyword) =>
  api.get('/api/ticket/station/search', { params: { keyword } });
export const getStationCities = () =>
  api.get('/api/ticket/station/cities');

// 乘车人管理
export const getPassengers = () => api.get('/api/user/passenger/list');
export const addPassenger = (data) => api.post('/api/user/passenger', data);
export const updatePassenger = (id, data) => api.put(`/api/user/passenger/${id}`, data);
export const deletePassenger = (id) => api.delete(`/api/user/passenger/${id}`);

// 个人资料
export const getProfile = () => api.get('/api/user/profile');
export const updateProfile = (data) => api.put('/api/user/profile', data);
export const changePassword = (data) => api.put('/api/user/profile/password', data);

export default api;
