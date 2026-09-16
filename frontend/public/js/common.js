/* Shared by restored pages and the product/order workflow. */
let commonURL = '/api';
let token = sessionStorage.getItem('token');
axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 12000;
axios.interceptors.request.use(config => {
  token = sessionStorage.getItem('token');
  if (token) config.headers.authorization = token;
  return config;
});
axios.interceptors.response.use(response => {
  if (!response.data.success) return Promise.reject(response.data.errorMsg || '操作未完成，请稍后重试');
  return response.data;
}, error => {
  const status = error.response && error.response.status;
  if (status === 401) {
    sessionStorage.removeItem('token');
    token = null;
    if (!location.pathname.startsWith('/login')) location.href = util.loginUrl();
    return Promise.reject('登录已失效，请重新登录');
  }
  if (status === 429) return Promise.reject('操作太频繁，请稍等片刻再试');
  if (!error.response) return Promise.reject('网络连接中断或请求超时。请检查连接；已提交的订单可在“我的订单”查看');
  return Promise.reject((error.response.data && error.response.data.errorMsg) || '服务暂不可用，请稍后重试');
});
axios.defaults.paramsSerializer = params => {
  const query = new URLSearchParams();
  Object.keys(params).forEach(key => {
    if (params[key] !== undefined && params[key] !== null && params[key] !== '') query.set(key, params[key]);
  });
  return query.toString();
};
const util = {
  commonURL,
  getUrlParam(name) { return new URLSearchParams(location.search).get(name) || ''; },
  safeReturn(value) {
    if (!value || !value.startsWith('/') || value.startsWith('//') || /[\\\u0000-\u001f]/.test(value)) return '/index.html';
    try {
      const url = new URL(value, location.origin);
      return url.origin === location.origin && !url.pathname.startsWith('/login') ? url.pathname + url.search + url.hash : '/index.html';
    } catch (_) { return '/index.html'; }
  },
  loginUrl() { return '/login.html?returnTo=' + encodeURIComponent(location.pathname + location.search); },
  requireLogin() {
    if (sessionStorage.getItem('token')) return true;
    location.href = this.loginUrl();
    return false;
  },
  // Historical callers pass a string (yuan → cents) or a number (cents → yuan).
  formatPrice(value) {
    if (value === null || value === undefined || value === '') return '--';
    const amount = Number(value);
    if (!Number.isFinite(amount)) return '--';
    return typeof value === 'string' ? Math.round(amount * 100) : (amount / 100).toFixed(2);
  }
};
