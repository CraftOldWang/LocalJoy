import {useRef, useState} from 'react';
import {Link, useNavigate, useSearchParams} from 'react-router-dom';
import {request, safeReturn} from '../domain.mjs';
import {useClock} from '../hooks.js';
import {Notice} from '../components.jsx';

export default function Login({onLogin}) {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const now = useClock();
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [errors, setErrors] = useState({});
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [sending, setSending] = useState(false);
  const [resendAt, setResendAt] = useState(0);
  const phoneRef = useRef(null), codeRef = useRef(null), submitting = useRef(false), requesting = useRef(false);
  const seconds = Math.max(0, Math.ceil((resendAt - now) / 1000));
  function validatePhone() {
    if (/^1[3-9]\d{9}$/.test(phone)) { setErrors({}); return true; }
    setErrors({phone: '请输入 11 位中国大陆手机号'}); phoneRef.current.focus(); return false;
  }
  async function sendCode() {
    if (requesting.current || seconds || !validatePhone()) return;
    requesting.current = true; setSending(true); setError(''); setNotice('');
    try {
      await request('/user/code?' + new URLSearchParams({phone}), {method: 'POST'});
      setResendAt(Date.now() + 60000); setNotice('验证码已生成。本地演示不发送短信，请按仓库启动文档从本机 Redis 获取。'); codeRef.current.focus();
    } catch (failure) { setError(failure.message); }
    finally { requesting.current = false; setSending(false); }
  }
  async function submit(event) {
    event.preventDefault();
    if (submitting.current || !validatePhone()) return;
    if (!/^\d{6}$/.test(code)) { setErrors({code: '请输入 6 位验证码'}); codeRef.current.focus(); return; }
    submitting.current = true; setBusy(true); setError('');
    try {
      const {data: token} = await request('/user/login', {method: 'POST', body: {phone, code}});
      if (!token || typeof token !== 'string') throw new Error('未收到有效登录结果，请重试');
      sessionStorage.setItem('token', token); onLogin(); navigate(safeReturn(params.get('returnTo')), {replace: true});
    } catch (failure) { setError(failure.message); }
    finally { submitting.current = false; setBusy(false); }
  }
  return <section className="login-layout"><div className="login-photo"><img src="/imgs/afternoon-tea-generated.png" alt="AI 生成的下午茶演示配图"/><div><p>日常的小确幸，</p><p>值得认真期待。</p><small>AI 生成演示配图</small></div></div><div className="login-panel"><Link className="back-link" to="/">← 先逛逛</Link><p className="eyebrow">WELCOME TO LOCALJOY</p><h1>欢迎回来。</h1><p className="muted intro">登录后，继续抢购与查看订单。</p><form noValidate onSubmit={submit}>
    <div className="field"><label htmlFor="phone">手机号</label><input ref={phoneRef} id="phone" type="tel" autoComplete="tel" inputMode="tel" maxLength={11} value={phone} onChange={event => setPhone(event.target.value.trim())} aria-invalid={Boolean(errors.phone)} aria-describedby="phone-error" placeholder="请输入 11 位手机号"/><p id="phone-error" className="field-error">{errors.phone}</p></div>
    <div className="field"><label htmlFor="code">验证码</label><div className="code-row"><input ref={codeRef} id="code" autoComplete="one-time-code" inputMode="numeric" maxLength={6} value={code} onChange={event => setCode(event.target.value.trim())} aria-invalid={Boolean(errors.code)} aria-describedby="code-error" placeholder="6 位验证码"/><button type="button" disabled={sending || seconds > 0} onClick={sendCode}>{sending ? '正在发送…' : seconds ? seconds + ' 秒后重发' : '获取验证码'}</button></div><p id="code-error" className="field-error">{errors.code}</p></div>
    <Notice error>{error}</Notice><Notice>{notice}</Notice><button className="primary wide" type="submit" disabled={busy} aria-busy={busy}>{busy ? '正在登录…' : '登录并继续'}</button>
  </form><p className="fineprint">本地学习演示，不发送真实短信。验证码获取方式见仓库启动文档；验证后首次登录会创建演示账户。</p></div></section>;
}
