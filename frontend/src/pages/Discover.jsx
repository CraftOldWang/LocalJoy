import {useEffect, useRef, useState} from 'react';
import {Link, useParams, useSearchParams} from 'react-router-dom';
import {request, validId, pageNumber} from '../domain.mjs';
import {useResource} from '../hooks.js';
import {Icon, Pager, Photo, ProductCard, State} from '../components.jsx';

export function Discover() {
  const [params, setParams] = useSearchParams();
  const query = params.get('q') || '';
  const page = pageNumber(params.get('page'));
  const [draft, setDraft] = useState(query);
  const [composing, setComposing] = useState(false);
  const input = useRef(null);
  const shops = useResource(signal => request('/shop/of/name?' + new URLSearchParams({name: query, current: page}), {signal}), [query, page]);
  const featured = useResource(signal => request('/product/list/1', {signal}), []);
  useEffect(() => setDraft(query), [query]);
  useEffect(() => {
    if (composing || draft === query) return;
    const timer = setTimeout(() => setParams(draft ? {q: draft, page: '1'} : {}), 300);
    return () => clearTimeout(timer);
  }, [draft, composing, query, setParams]);
  function search(event) { event.preventDefault(); if (!composing) setParams(draft ? {q: draft, page: '1'} : {}); }
  function clear() { setDraft(''); setParams({}); input.current.focus(); }
  return <>
    <section className="hero">
      <div className="hero-copy"><p className="eyebrow"><span className="small-dot"/> 杭州 · 把生活过得有滋有味</p><h1>好日子，<br/>从附近的好物开始<span>。</span></h1><p>一杯午后的奶茶，一顿相聚的晚餐。<br/>发现店铺好物，给日常一点小期待。</p><a className="button primary" href="#today">看看今日好物 <Icon name="arrow"/></a><div className="hero-note"><Icon name="pin"/> 附近店铺 <span>·</span> 限时好价 <span>·</span> 随时查看订单</div></div>
      <figure className="hero-photo"><img src="/imgs/afternoon-tea-generated.png" alt="港式奶茶、菠萝包与蛋挞组成的下午茶，AI 生成演示配图"/><figcaption>留一点时间，给生活里的小确幸。<small>AI 生成演示配图</small></figcaption></figure>
    </section>
    <section id="today" className="section"><div className="section-heading"><div><p className="eyebrow">TODAY'S PICKS</p><h2>今天，想来点什么？</h2></div><Link to="/shops/1">逛逛这家店 <Icon name="arrow"/></Link></div>
      {featured.loading || featured.error || !featured.data?.data?.length ? <State loading={featured.loading} error={featured.error} empty="店铺还没有上架好物" onRetry={featured.reload}>可以先浏览下方店铺，稍后再来。</State> : <div className="grid">{featured.data.data.slice(0, 4).map(product => <ProductCard key={product.id} product={product}/>)}</div>}
    </section>
    <section className="section" id="shops"><div className="section-heading"><div><p className="eyebrow">AROUND THE CORNER</p><h2>附近店铺</h2></div><span className="help">杭州课程示例店铺</span></div>
      <form className="search-form" noValidate onSubmit={search}><label className="sr-only" htmlFor="shop-search">搜索店铺名称</label><Icon name="search"/><input id="shop-search" ref={input} value={draft} onChange={event => setDraft(event.target.value)} onCompositionStart={() => setComposing(true)} onCompositionEnd={() => setComposing(false)} placeholder="搜索店铺名称，比如茶餐厅" autoComplete="off"/>{draft && <button type="button" className="clear-button" aria-label="清除店铺搜索" onClick={clear}>×</button>}<button type="submit">搜索</button></form>
      {shops.loading || shops.error || !shops.data?.data?.length ? <State loading={shops.loading} error={shops.error} empty={query ? '没有找到这家店' : '暂时没有店铺'} onRetry={shops.reload}>{query ? '试试更短的店名，或者清除搜索。' : '请稍后刷新。'}</State> : <div className="shop-grid">{shops.data.data.map(shop => <Link className="shop-card" key={shop.id} to={'/shops/' + shop.id}><Photo src={shop.images?.split(',')[0]} alt={shop.name} className="shop-thumb"/><div><h3>{shop.name}</h3><p className="help">{shop.area} · {shop.address}</p><span className="shop-meta">评分 {shop.score != null ? (shop.score / 10).toFixed(1) : '--'} <span>人均 ¥{shop.avgPrice ?? '--'}</span></span></div><Icon name="arrow"/></Link>)}</div>}
      <Pager page={page} disabled={shops.loading} hasNext={(shops.data?.data?.length || 0) >= 10} onChange={next => setParams({...(query ? {q: query} : {}), page: String(next)})}/>
    </section>
  </>;
}

export function Shop() {
  const {id} = useParams();
  const resource = useResource(async signal => {
    if (!validId(id)) throw new Error('店铺地址不正确，请返回首页');
    const [shop, products] = await Promise.all([request('/shop/' + id, {signal}), request('/product/list/' + id, {signal})]);
    if (!shop.data) throw new Error('店铺不存在');
    return {shop: shop.data, products: products.data || []};
  }, [id]);
  if (resource.loading || resource.error) return <State {...resource} onRetry={resource.reload}/>;
  const {shop, products} = resource.data;
  return <><Link className="back-link" to="/">← 返回附近好物</Link><section className="shop-intro card"><Photo src={shop.images?.split(',')[0]} alt={shop.name} className="shop-cover"/><div className="card-body"><p className="eyebrow">到店发现 · 杭州</p><h1>{shop.name}</h1><p className="muted"><Icon name="pin"/> {shop.address}</p><p className="muted"><Icon name="clock"/> 营业时间 {shop.openHours || '请联系店铺确认'}</p><div className="row"><span className="tag">评分 {((shop.score || 0) / 10).toFixed(1)}</span><span className="tag">人均 ¥{shop.avgPrice ?? '--'}</span></div></div></section>
    <section className="section"><div className="section-heading"><div><p className="eyebrow">SHOP SPECIALS</p><h2>本店限时好物</h2></div><span className="help">{products.length} 款商品</span></div>{products.length ? <div className="grid">{products.map(product => <ProductCard product={product} key={product.id}/>)}</div> : <State empty="这家店还没有上架商品">先收藏这份期待，稍后再来看看。</State>}</section>
  </>;
}
