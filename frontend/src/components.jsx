import {Link} from 'react-router-dom';
import {activity, imageUrl, price} from './domain.mjs';
import {useClock} from './hooks.js';

export function Icon({name, ...props}) {
  const paths = {
    bag: <><path d="M5 7h14l1 14H4L5 7Z"/><path d="M8 8V6a4 4 0 0 1 8 0v2"/></>,
    arrow: <><path d="M5 12h14M13 6l6 6-6 6"/></>,
    pin: <><path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/></>,
    receipt: <><path d="M6 3h12v18l-3-2-3 2-3-2-3 2V3Z"/><path d="M9 8h6M9 12h6"/></>,
    search: <><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/></>,
    user: <><circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/></>,
    check: <path d="m5 12 4 4L19 6"/>
  };
  return <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{paths[name] || paths.bag}</svg>;
}
export function Photo({src, alt, className = 'product-image'}) {
  return <img className={className} src={imageUrl(src)} alt={alt} loading="lazy" onError={event => { if (!event.currentTarget.src.endsWith('product-placeholder.svg')) event.currentTarget.src = '/imgs/product-placeholder.svg'; }}/>;
}
export function State({loading, error, empty = '暂时没有内容', onRetry, children}) {
  return <section className="card state" aria-live="polite" aria-busy={loading}>
    {loading ? <><span className="spinner" aria-hidden="true"/><p>正在加载，请稍候…</p></> : error ? <><h2>暂时没能加载</h2><p className="muted">{error}</p><button type="button" onClick={onRetry}>重新加载</button></> : <><Icon name="bag" width="32" height="32"/><h2>{empty}</h2><div className="muted">{children}</div></>}
  </section>;
}
export function Notice({error = false, children}) {
  return children ? <div className={'notice' + (error ? ' error' : '')} role={error ? 'alert' : 'status'}>{children}</div> : null;
}
export function ProductCard({product}) {
  const now = useClock();
  return <article className="card product-card">
    <Link className="image-link" to={'/products/' + product.id} aria-label={'查看' + product.title}>
      <Photo src={product.image} alt={product.title}/>
      <span className="image-label">{product.image?.includes('generated') ? 'AI 演示配图' : '店铺商品'}</span>
    </Link>
    <div className="card-body"><div className="row spread"><span className="tag waiting">{activity(product, now)}</span><span className="help">{product.stock != null ? '剩余 ' + product.stock + ' 份' : '店铺好物'}</span></div>
      <h3><Link to={'/products/' + product.id}>{product.title.replace('LocalJoy 演示 · ', '')}</Link></h3><p className="muted">{product.subTitle || '到店发现一份小确幸。'}</p>
      <div className="ticket row spread"><span className="price"><small>¥</small> {price(product.price)}</span><Link className="button" to={'/products/' + product.id}>去看看 <Icon name="arrow"/></Link></div>
    </div>
  </article>;
}
export function Pager({page, hasNext, disabled, onChange, total}) {
  return <nav className="pager" aria-label="分页"><button type="button" disabled={disabled || page <= 1} onClick={() => onChange(page - 1)}>上一页</button><span>第 {page} 页{total != null ? ' / 共 ' + total + ' 笔' : ''}</span><button type="button" disabled={disabled || !hasNext} onClick={() => onChange(page + 1)}>下一页</button></nav>;
}
