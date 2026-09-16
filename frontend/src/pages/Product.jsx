import {useRef, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import {activity, price, request, time, validId} from '../domain.mjs';
import {useClock, useResource} from '../hooks.js';
import {Icon, Notice, Photo, State} from '../components.jsx';

export default function Product() {
  const {id} = useParams();
  const navigate = useNavigate();
  const now = useClock();
  const sending = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const resource = useResource(async signal => {
    if (!validId(id)) throw new Error('商品地址不正确，请返回店铺页面');
    const {data: product} = await request('/product/' + id, {signal});
    const {data: list} = await request('/product/list/' + product.shopId, {signal});
    return {product, offer: list?.find(item => String(item.id) === id) || null};
  }, [id]);
  async function buy() {
    if (sending.current || activity(resource.data?.offer) !== '抢购中') return;
    if (!sessionStorage.getItem('token')) { navigate('/login?returnTo=' + encodeURIComponent('/products/' + id)); return; }
    sending.current = true; setBusy(true); setError('');
    try {
      const {data: orderId} = await request('/product-order/seckill/' + id, {method: 'POST'});
      if (typeof orderId !== 'string' || !validId(orderId)) throw new Error('订单号异常，请前往“我的订单”确认结果');
      navigate('/orders/' + orderId + '?accepted=1');
    } catch (failure) { setError(failure.message); }
    finally { sending.current = false; setBusy(false); }
  }
  if (resource.loading || resource.error) return <State {...resource} onRetry={resource.reload}/>;
  const {product, offer} = resource.data;
  const label = activity(offer, now);
  return <><Link className="back-link" to={'/shops/' + product.shopId}>← 返回店铺好物</Link>
    <article className="card detail"><div className="detail-photo"><Photo src={product.image} alt={product.title}/>{product.image?.includes('generated') && <span className="image-label">AI 生成演示配图</span>}</div>
      <div className="card-body detail-body"><p className="eyebrow">一份好物 · 一点期待</p><h1>{product.title.replace('LocalJoy 演示 · ', '')}</h1><p className="muted">{product.subTitle}</p>
        <div className="ticket row spread"><span className="price"><small>¥</small> {price(product.price)}</span><span className="tag waiting">{label}</span></div>
        <dl className="facts"><dt>剩余数量</dt><dd>{offer?.stock == null ? '未开放抢购' : offer.stock + ' 份'}</dd><dt>开始时间</dt><dd>{time(offer?.beginTime)}</dd><dt>结束时间</dt><dd>{time(offer?.endTime)}</dd></dl>
        <button className="primary wide" disabled={busy || label !== '抢购中'} aria-busy={busy} onClick={buy}>{busy ? '正在提交…' : label === '抢购中' ? '立即抢购' : label}<Icon name="arrow"/></button>
        <p className="help">每人限一笔有效订单 · 下单后限时支付</p><Notice error>{error && <>{error} <Link to="/orders">查看我的订单</Link></>}</Notice>
      </div>
    </article>
    <section className="section detail-notes"><div><h2>这份好物</h2><p className="description muted">{product.description || '店铺暂未填写详细介绍。'}</p></div><div className="purchase-note"><h3>购买须知</h3><p>库存以提交时的结果为准。抢购受理后请等待订单确认；超时未支付的订单会自动关闭。</p><p>此为本地学习演示，不提供真实交易、到店核销或退款。</p></div></section>
  </>;
}
