import axios from 'axios';

// In development Vite forwards /api to the gateway. In Docker, Nginx does it.
// The browser therefore never calls a microservice directly.
const api = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
});

export const getProducts = () => api.get('/inventory/products').then((r) => r.data);
export const getOrders = () => api.get('/orders').then((r) => r.data);
export const createOrder = (payload) => api.post('/orders', payload).then((r) => r.data);
export const getPaymentsForOrder = (orderId) =>
  api.get(`/payments/order/${encodeURIComponent(orderId)}`).then((r) => r.data);
export const getNotifications = () => api.get('/notifications').then((r) => r.data);

export function readableError(error) {
  if (error.response?.data?.message) return error.response.data.message;
  if (error.response?.status === 409) return 'There is not enough stock for one or more items.';
  if (error.response?.status === 402) return 'Payment was declined. Any reserved stock has been released.';
  if (error.code === 'ECONNABORTED') return 'The gateway took too long to respond. Please try again.';
  if (!error.response) return 'Could not reach the API gateway. Check that the project is running.';
  return `The request failed (${error.response.status}). Please try again.`;
}
