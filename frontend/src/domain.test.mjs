import test from 'node:test';
import assert from 'node:assert/strict';
import {activity, imageUrl, pageNumber, price, request, safeReturn, timestamp, validId} from './domain.mjs';

test('login return destination stays on the same origin', () => {
  const origin = 'http://127.0.0.1:8088';
  for (const value of ['//evil.example', '/\\evil.example', 'https://evil.example', '/login', '/\u0000evil']) assert.equal(safeReturn(value, origin), '/');
  assert.equal(safeReturn('/products/12?from=shop', origin), '/products/12?from=shop');
});
test('order identifiers are validated as strings without numeric conversion', () => {
  assert.ok(validId('9007199254740993'));
  for (const value of ['1e5', '-1', '0', 'NaN', '', '99999999999999999999']) assert.equal(validId(value), false);
});
test('China time and activity edge conditions match the API contract', () => {
  assert.equal(timestamp('2026-09-16T10:00:00'), Date.parse('2026-09-16T02:00:00Z'));
  const product = {beginTime: '2026-09-16T10:00:00', endTime: '2026-09-16T11:00:00', stock: 1};
  assert.equal(activity(product, timestamp(product.beginTime) - 1), '尚未开始');
  assert.equal(activity(product, timestamp(product.beginTime)), '抢购中');
  assert.equal(activity({...product, stock: 0}, timestamp(product.beginTime)), '已售罄');
  assert.equal(activity(product, timestamp(product.endTime)), '活动已结束');
  assert.equal(activity(null), '暂无秒杀活动');
});
test('amount, page and image inputs have bounded fallbacks', () => {
  assert.equal(price(1990), '19.90'); assert.equal(price(0), '0.00'); assert.equal(price(null), '--');
  assert.equal(pageNumber('-1'), 1); assert.equal(pageNumber('1.8'), 1); assert.equal(pageNumber('99999'), 10000);
  assert.equal(imageUrl('javascript:alert(1)'), '/imgs/product-placeholder.svg');
});
test('HTTP adapter distinguishes login, rate limiting, business and network failures', async () => {
  const original = {fetch: globalThis.fetch, storage: globalThis.sessionStorage, window: globalThis.window};
  let cleared = false, expired = false;
  globalThis.sessionStorage = {getItem: () => 'test-token', removeItem: () => {cleared = true;}};
  globalThis.window = {dispatchEvent: () => {expired = true;}};
  try {
    globalThis.fetch = async () => new Response('', {status: 401});
    await assert.rejects(request('/orders'), error => error.status === 401);
    assert.ok(cleared && expired);
    globalThis.fetch = async () => new Response('', {status: 429});
    await assert.rejects(request('/orders'), error => error.status === 429);
    globalThis.fetch = async () => new Response(JSON.stringify({success: false, errorMsg: '库存不足'}));
    await assert.rejects(request('/orders'), /库存不足/);
    globalThis.fetch = async () => {throw new TypeError('offline');};
    await assert.rejects(request('/orders'), /网络连接中断/);
    globalThis.fetch = async () => new Response(JSON.stringify({success: true, data: '9007199254740993'}));
    assert.equal((await request('/orders')).data, '9007199254740993');
  } finally {globalThis.fetch = original.fetch; globalThis.sessionStorage = original.storage; globalThis.window = original.window;}
});
