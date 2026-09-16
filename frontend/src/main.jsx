import React, {useEffect, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {BrowserRouter, Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate, useSearchParams} from 'react-router-dom';
import {Discover, Shop} from './pages/Discover.jsx';
import Product from './pages/Product.jsx';
import {Order, Orders} from './pages/Orders.jsx';
import Login from './pages/Login.jsx';
import {Icon, Notice, State} from './components.jsx';
import {request} from './domain.mjs';
import './styles.css';

function Protected({loggedIn, children}) {
  const location = useLocation();
  return loggedIn ? children : <Navigate to={'/login?returnTo=' + encodeURIComponent(location.pathname + location.search)} replace/>;
}
function Compat({kind}) {
  const [params] = useSearchParams();
  const id = params.get('id'), shop = params.get('shopId') || '1';
  const target = kind === 'product' ? '/products/' + (id || '') : kind === 'order' ? '/orders/' + (id || '') + (params.get('accepted') ? '?accepted=1' : '') : kind === 'orders' ? '/orders' : kind === 'login' ? '/login?' + params.toString() : '/shops/' + shop;
  return <Navigate to={target} replace/>;
}
function App() {
  const [loggedIn, setLoggedIn] = useState(Boolean(sessionStorage.getItem('token')));
  const [user, setUser] = useState(null);
  const [error, setError] = useState('');
  const [loggingOut, setLoggingOut] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const loginTarget = location.pathname === '/login' ? location.pathname + location.search : '/login?returnTo=' + encodeURIComponent(location.pathname + location.search);
  useEffect(() => {
    const expired = () => { setLoggedIn(false); setUser(null); if (!location.pathname.startsWith('/login')) navigate('/login?returnTo=' + encodeURIComponent(location.pathname + location.search)); };
    window.addEventListener('localjoy:auth-expired', expired);
    return () => window.removeEventListener('localjoy:auth-expired', expired);
  }, [navigate, location.pathname, location.search]);
  useEffect(() => {
    if (!loggedIn) return;
    const controller = new AbortController();
    request('/user/me', {signal: controller.signal}).then(result => setUser(result.data)).catch(() => {});
    return () => controller.abort();
  }, [loggedIn]);
  useEffect(() => { window.scrollTo(0, 0); setError(''); document.title = location.pathname.startsWith('/orders') ? '我的订单 · 趣享生活' : location.pathname.startsWith('/login') ? '登录 · 趣享生活' : '附近好物 · 趣享生活 LocalJoy'; }, [location.pathname]);
  async function logout() {
    if (loggingOut) return;
    setLoggingOut(true);
    try { await request('/user/logout', {method: 'POST'}); sessionStorage.removeItem('token'); sessionStorage.removeItem('userInfo'); setLoggedIn(false); setUser(null); navigate('/'); }
    catch (failure) { setError(failure.message); }
    finally { setLoggingOut(false); }
  }
  return <><a className="skip-link" href="#main">跳到主要内容</a><header className="topbar"><div className="topbar-inner"><Link className="brand" to="/" aria-label="趣享生活 LocalJoy 首页"><span className="brand-icon"><Icon name="bag"/></span>趣享生活 <small>LocalJoy</small></Link><nav aria-label="主要导航"><NavLink to="/" end>附近好物</NavLink><NavLink to="/orders">我的订单</NavLink></nav><div className="account">{loggedIn ? <><span className="account-name">{user?.nickName || '已登录'}</span><button className="quiet-button" disabled={loggingOut} onClick={logout}>{loggingOut ? '退出中…' : '退出'}</button></> : <Link className="login-link" to={loginTarget}><Icon name="user"/> 登录</Link>}</div></div></header>
    <main id="main" className="page"><Notice error>{error}</Notice><Routes>
      <Route path="/" element={<Discover/>}/><Route path="/shops/:id" element={<Shop/>}/><Route path="/products/:id" element={<Product/>}/>
      <Route path="/orders" element={<Protected loggedIn={loggedIn}><Orders/></Protected>}/><Route path="/orders/:id" element={<Protected loggedIn={loggedIn}><Order key={location.pathname}/></Protected>}/>
      <Route path="/login" element={<Login onLogin={() => setLoggedIn(true)}/>}/>
      <Route path="/index.html" element={<Navigate to="/" replace/>}/>
      {['product', 'products', 'order', 'orders', 'login'].map(kind => <Route key={kind} path={'/' + kind + '.html'} element={<Compat kind={kind}/>}/>)}
      <Route path="*" element={<State empty="这个页面还没有到来"><Link to="/">返回附近好物 →</Link></State>}/>
    </Routes></main>
    <footer className="site-footer"><div><strong>趣享生活 LocalJoy</strong><p>发现附近的好物，认真过好每一天。</p></div><div><p>本地学习演示 · 模拟支付，不发生真实扣款</p><a href="/legacy/index.html">原版页面存档</a><span> · </span><a href="https://github.com/CraftOldWang/LocalJoy#readme" target="_blank" rel="noreferrer">项目文档 ↗</a></div></footer>
    <nav className="mobile-nav" aria-label="移动端导航"><NavLink to="/" end><Icon name="bag"/>附近好物</NavLink><NavLink to="/orders"><Icon name="receipt"/>我的订单</NavLink></nav>
  </>;
}
createRoot(document.getElementById('root')).render(<React.StrictMode><BrowserRouter><App/></BrowserRouter></React.StrictMode>);
