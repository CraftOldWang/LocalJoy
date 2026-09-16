export function validId(value) { return /^[1-9]\d{0,18}$/.test(String(value || '')); }
export function price(cents) { return cents == null ? '--' : (Number(cents) / 100).toFixed(2); }
export function time(value) { return value ? value.replace('T', ' ').slice(0, 19) : '待确认'; }
export function timestamp(value) {
  if (!value) return NaN;
  return Date.parse(/[zZ]|[+-]\d{2}:?\d{2}$/.test(value) ? value : value.replace(' ', 'T') + '+08:00');
}
export function activity(product, now = Date.now()) {
  if (!product || !product.beginTime || !product.endTime || product.stock == null) return '暂无秒杀活动';
  const begin = timestamp(product.beginTime), end = timestamp(product.endTime);
  if (!Number.isFinite(begin) || !Number.isFinite(end)) return '活动信息待确认';
  if (now < begin) return '尚未开始';
  if (now >= end) return '活动已结束';
  if (product.stock <= 0) return '已售罄';
  return '抢购中';
}
export function orderStatus(order) { return ({1: '待支付', 2: '已支付', 4: '已关闭'})[order.status] || '状态待确认'; }
export function closeReason(order) {
  return ({TIMEOUT_AUTO_CLOSE: '超过支付期限，订单已自动关闭。', DUPLICATE_BUYER: '你已有该商品的有效订单，本次订单已关闭。', DB_STOCK_EXHAUSTED: '商品库存不足，本次订单已关闭。'})[order.closeReason] || '订单已关闭，请返回商品页查看当前活动。';
}
export function safeReturn(value, origin = window.location.origin) {
  if (!value || !value.startsWith('/') || value.startsWith('//') || /[\\\u0000-\u001f]/.test(value)) return '/';
  try {
    const url = new URL(value, origin);
    return url.origin === origin && !url.pathname.startsWith('/login') ? url.pathname + url.search + url.hash : '/';
  } catch { return '/'; }
}
export function imageUrl(value) {
  return value && /^(\/[^/]|https?:\/\/)/.test(value) ? value : '/imgs/product-placeholder.svg';
}
export function pageNumber(value) { return Math.max(1, Math.min(10000, Math.trunc(Number(value) || 1))); }

export class ApiError extends Error {
  constructor(message, status = 0) { super(message); this.status = status; }
}
export async function request(path, {method = 'GET', body, signal} = {}) {
  const token = sessionStorage.getItem('token');
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  signal?.addEventListener('abort', abort, {once: true});
  const timer = setTimeout(abort, 12000);
  try {
    const response = await fetch('/api' + path, {
      method, signal: controller.signal,
      headers: {...(token ? {authorization: token} : {}), ...(body !== undefined ? {'Content-Type': 'application/json'} : {})},
      ...(body !== undefined ? {body: JSON.stringify(body)} : {})
    });
    if (response.status === 401) {
      sessionStorage.removeItem('token');
      window.dispatchEvent(new Event('localjoy:auth-expired'));
      throw new ApiError('登录已失效，请重新登录', 401);
    }
    if (response.status === 429) throw new ApiError('操作太频繁，请稍等片刻再试', 429);
    const result = await response.json().catch(() => null);
    if (!response.ok) throw new ApiError(result?.errorMsg || '服务暂不可用，请稍后重试', response.status);
    if (!result?.success) throw new ApiError(result?.errorMsg || '操作未完成，请稍后重试', 200);
    return result;
  } catch (error) {
    if (signal?.aborted || error instanceof ApiError) throw error;
    throw new ApiError('网络连接中断或请求超时，请稍后重试。已提交的订单请先在“我的订单”确认。');
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}
