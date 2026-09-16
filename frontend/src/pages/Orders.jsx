import {useEffect, useRef, useState} from 'react';
import {Link, useParams, useSearchParams} from 'react-router-dom';
import {closeReason, orderStatus, pageNumber, request, time, timestamp, validId} from '../domain.mjs';
import {useClock, useResource} from '../hooks.js';
import {Icon, Notice, Pager, State} from '../components.jsx';

export function Orders() {
  const [params, setParams] = useSearchParams();
  const page = pageNumber(params.get('page'));
  const resource = useResource(signal => request('/product-order/list?current=' + page, {signal}), [page]);
  const orders = resource.data?.data || [];
  const total = resource.data?.total || 0;
  useEffect(() => {
    if (!resource.loading && !resource.error && !orders.length && page > 1) setParams({page: String(Math.max(1, Math.ceil(total / 10)))}, {replace: true});
  }, [resource.loading, resource.error, orders.length, page, total, setParams]);
  return <><section className="intro row spread"><div><p className="eyebrow">YOUR LITTLE JOYS</p><h1>每一份期待，都有记录。</h1><p className="muted">查看抢购结果，完成模拟支付。</p></div><button onClick={resource.reload} disabled={resource.loading}>刷新订单</button></section>
    {resource.loading || resource.error || !orders.length ? <State {...resource} empty="还没有商品订单" onRetry={resource.reload}><Link to="/">去发现附近好物 →</Link></State> : <div className="stack">{orders.map(order => <article key={order.id} className="card card-body order-card">
      <div className="order-symbol"><Icon name="receipt" width="28" height="28"/></div><div className="order-summary"><div className="row spread"><h2>商品 #{order.productId}</h2><span className={'tag ' + (order.status === 2 ? 'success' : order.status === 1 ? 'waiting' : '')}>{orderStatus(order)}</span></div><p className="order-id">订单号 {order.id}</p><p className="help">{time(order.createTime)}</p></div><Link className="button" to={'/orders/' + order.id}>查看详情 <Icon name="arrow"/></Link>
    </article>)}</div>}
    {total > 10 && <Pager page={page} total={total} disabled={resource.loading} hasNext={page * 10 < total} onChange={next => setParams({page: String(next)})}/>}
    <p className="fineprint">共 {total} 笔商品订单，每页 10 笔。刚受理的订单可能需要稍等片刻才会出现。</p>
  </>;
}

export function Order() {
  const {id} = useParams();
  const [params] = useSearchParams();
  const accepted = params.get('accepted') === '1';
  const now = useClock();
  const [revision, setRevision] = useState(0);
  const [view, setView] = useState({order: null, loading: true, checking: true, error: '', attempts: 0});
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [payError, setPayError] = useState('');
  const sending = useRef(false);
  useEffect(() => {
    const controller = new AbortController();
    let timer, attempts = 0;
    setView({order: null, loading: true, checking: true, error: '', attempts: 0});
    async function load() {
      attempts++;
      let order = null;
      try {
        if (!validId(id)) throw new Error('订单地址不正确，请从“我的订单”进入');
        order = (await request('/product-order/' + id, {signal: controller.signal})).data;
        if (controller.signal.aborted) return;
        setView({order, loading: false, checking: false, error: '', attempts});
      } catch (error) {
        if (controller.signal.aborted) return;
        setView(current => ({...current, loading: false, checking: false, error: error.message, attempts}));
      }
      if (!controller.signal.aborted && validId(id) && attempts < 30 && ((order && order.status === 1) || (!order && accepted))) {
        timer = setTimeout(load, order ? 5000 : 2000);
      }
    }
    load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [id, revision, accepted]);
  const order = view.order;
  const remaining = order ? Math.max(0, Math.ceil((timestamp(order.expireTime) - now) / 1000)) : 0;
  const canPay = order?.status === 1 && remaining > 0;
  async function pay() {
    if (sending.current || !canPay) return;
    sending.current = true; setBusy(true); setNotice(''); setPayError('');
    try { await request('/product-order/pay/' + id, {method: 'POST'}); setNotice('模拟支付确认成功，未发生真实扣款。'); }
    catch (error) { setPayError(error.message); }
    finally { sending.current = false; setBusy(false); setRevision(value => value + 1); }
  }
  const refresh = () => setRevision(value => value + 1);
  return <><Link className="back-link" to="/orders">← 返回我的订单</Link><section className="intro"><p className="eyebrow">ORDER PROGRESS</p><h1>{order ? orderStatus(order) : accepted ? '正在确认你的好物' : '订单详情'}</h1><p className="order-id">订单号 {id}</p></section>
    {!order && accepted && validId(id) ? <section className="card state" aria-live="polite" aria-busy={view.checking}>{view.attempts < 30 && <span className="spinner" aria-hidden="true"/>}<h2>{view.attempts < 30 ? '抢购申请已受理' : '暂未查到订单结果'}</h2><p className="muted">{view.attempts < 30 ? '正在查询建单结果，请勿重复提交。' : '自动查询已暂停，可手动刷新，或稍后查看我的订单。'}</p>{view.error && <p className="help">{view.error}</p>}<button onClick={refresh} disabled={view.checking}>刷新结果</button></section> : !order ? <State {...view} onRetry={refresh}/> : <section className="card card-body order-detail">
      <ol className="steps" aria-label="订单进度"><li className="complete"><Icon name="check"/> 已受理</li><li className="complete"><Icon name="check"/> 已建单</li><li className={order.status === 2 ? 'complete' : ''}><Icon name={order.status === 2 ? 'check' : 'clock'}/>{order.status === 4 ? '已关闭' : order.status === 2 ? '已支付' : '待支付'}</li></ol>
      <div className="row spread"><h2>商品 #{order.productId}</h2><Link to={'/products/' + order.productId}>查看商品 →</Link></div>
      <Notice>{order.status === 1 ? remaining > 0 ? `请在 ${Math.floor(remaining / 60)} 分 ${String(remaining % 60).padStart(2, '0')} 秒内完成模拟支付。` : '支付期限已到，正在等待关单结果。' : order.status === 2 ? '模拟支付已确认。此订单未发生真实扣款。' : closeReason(order)}</Notice>
      <dl className="facts"><dt>创建时间</dt><dd>{time(order.createTime)}</dd><dt>支付截止</dt><dd>{time(order.expireTime)}</dd>{order.payTime && <><dt>确认时间</dt><dd>{time(order.payTime)}</dd></>}</dl>
      <div className="row">{order.status === 1 && <button className="primary" disabled={busy || !canPay} aria-busy={busy} onClick={pay}>{busy ? '正在确认…' : '模拟支付（不扣款）'}</button>}<button onClick={refresh} disabled={busy || view.checking}>刷新状态</button></div>
      <Notice error>{view.error && <>{view.error} 当前保留上次结果，请刷新确认。</>}</Notice>
    </section>}
    <Notice>{notice}</Notice><Notice error>{payError}</Notice><p className="fineprint">状态以服务端结果为准。自动查询最多 30 次，长时间停留后可手动刷新。当前不支持真实付款、退款与核销。</p>
  </>;
}
