import {useEffect, useRef, useState} from 'react';
import QRCode from 'qrcode';
import {price, request} from './domain.mjs';
import {Notice} from './components.jsx';

const explanations = {
  CREATING: '正在向支付宝确认预下单结果，请稍后查询。',
  WAITING: '请用沙箱钱包扫描二维码，使用沙箱买家账户付款。',
  UNKNOWN: '预下单结果尚未确认。请先查询支付结果，避免重复提交。',
  CLOSING: '正在核对渠道交易并关闭订单，确认前会保留库存。',
  CLOSED: '支付宝交易已关闭，正在等待本地订单完成关单。',
  SUCCEEDED: '支付宝沙箱支付已确认，未发生真实资金扣款。',
  REVIEW_REQUIRED: '支付与订单结果不一致，已保留记录，需要人工核账。请勿重复支付。'
};

export default function PaymentPanel({order, canPay, onPaid}) {
  const [view, setView] = useState(null);
  const [options, setOptions] = useState(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState('');
  const [revision, setRevision] = useState(0);
  const [qr, setQr] = useState('');
  const writing = useRef(false);
  const readVersion = useRef(0);
  const writeController = useRef(null);
  useEffect(() => () => writeController.current?.abort(), []);
  // The parent polls the local order. This read does not call Alipay or create a new payment.
  useEffect(() => {
    const controller = new AbortController();
    const currentRead = ++readVersion.current;
    Promise.all([
      request('/payment/options', {signal: controller.signal}),
      request('/payment/alipay/' + order.id, {signal: controller.signal})
    ]).then(([settings, payment]) => {
      if (!controller.signal.aborted && currentRead === readVersion.current && !writing.current) { setOptions(settings.data); setView(payment.data); setError(''); }
    }).catch(err => { if (!controller.signal.aborted && currentRead === readVersion.current && !writing.current) setError(err.message); });
    return () => controller.abort();
  }, [order, revision]);
  const qrValue = canPay && view?.state === 'WAITING' ? view.qrCode : '';
  useEffect(() => {
    let active = true;
    setQr('');
    if (qrValue) QRCode.toDataURL(qrValue, {width: 256, margin: 4, errorCorrectionLevel: 'M'})
      .then(url => { if (active) setQr(url); })
      .catch(() => { if (active) setError('二维码显示失败，请刷新支付状态。'); });
    return () => { active = false; };
  }, [qrValue, revision]);
  async function act(action) {
    if (writing.current) return;
    writing.current = true; setBusy(action); setError(''); setNotice('');
    ++readVersion.current;
    const controller = new AbortController(); writeController.current = controller;
    try {
      const path = action === 'mock' ? '/product-order/pay/' + order.id : '/payment/alipay/' + order.id + (action === 'sync' ? '/sync' : '');
      const response = await request(path, {method: 'POST', signal: controller.signal});
      if (controller.signal.aborted) return;
      if (action === 'mock' || response.data?.state === 'SUCCEEDED') { onPaid(); return; }
      setView(response.data);
      if (action === 'sync') setNotice('已查询支付结果，请以页面显示的状态为准。');
    } catch (err) { if (!controller.signal.aborted) setError(err.message + ' 请先刷新支付状态再操作。'); }
    finally { ++readVersion.current; writing.current = false; if (!controller.signal.aborted) { setBusy(''); setRevision(value => value + 1); } }
  }
  const hasAttempt = view && view.state !== 'NONE';
  const terminal = ['SUCCEEDED', 'CLOSED', 'REVIEW_REQUIRED'].includes(view?.state);
  return <section className="payment-panel" aria-labelledby="payment-heading">
    <div className="row spread"><h3 id="payment-heading">支付方式</h3><span className="tag">开发测试 · 不扣真实资金</span></div>
    {order.totalAmount != null && <p>订单金额 <strong className="payment-amount">¥{price(order.totalAmount)}</strong></p>}
    {!view && !error && <p role="status">正在读取支付状态…</p>}
    {view && !hasAttempt && order.status === 1 && <>
      <p className="muted">选择支付宝沙箱，可体验扫码付款与结果确认；本地模拟则直接完成演示订单。</p>
      <div className="row">
        <button className="primary" disabled={!!busy || !!error || !canPay || !options?.alipayReady || order.totalAmount == null} aria-busy={busy === 'start'} onClick={() => act('start')}>{busy === 'start' ? '正在获取二维码…' : '支付宝沙箱支付'}</button>
        {options?.mockEnabled && <button disabled={!!busy || !!error || !canPay} aria-busy={busy === 'mock'} onClick={() => act('mock')}>{busy === 'mock' ? '正在确认…' : '本地模拟支付（不扣款）'}</button>}
      </div>
      {!options?.alipayReady && <p className="help">支付宝沙箱尚未配置，可先使用本地模拟支付。</p>}
      {order.totalAmount == null && <p className="help">这笔历史订单没有金额快照，请重新下单体验支付宝沙箱。</p>}
    </>}
    {hasAttempt && <>
      <Notice>{view.state === 'WAITING' && !canPay ? '支付期限已到，请查询支付结果，不要再扫码付款。' : explanations[view.state] || '支付状态待核对，请稍后查询。'}</Notice>
      {qrValue && <div className="payment-qr">{qr ? <img src={qr} width="256" height="256" alt="支付宝沙箱订单支付二维码"/> : <span role="status">正在显示二维码…</span>}<p className="help">请使用沙箱钱包，普通支付宝不能用于此测试。</p></div>}
      {!terminal && <button disabled={!!busy || !!error} aria-busy={busy === 'sync'} onClick={() => act('sync')}>{busy === 'sync' ? '正在查询…' : '查询支付结果'}</button>}
      {view.tradeNo && <p className="help order-id">支付宝交易号 {view.tradeNo}</p>}
    </>}
    <Notice>{notice}</Notice><Notice error>{error}</Notice>
    {error && <button disabled={!!busy} onClick={() => setRevision(value => value + 1)}>刷新支付状态</button>}
  </section>;
}
