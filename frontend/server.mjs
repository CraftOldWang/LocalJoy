import http from 'node:http';
import https from 'node:https';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Serve a built React SPA and the archived pages on one local origin.
const root = fileURLToPath(new URL('./dist/', import.meta.url));
if (!(await stat(path.join(root, 'index.html')).catch(() => null))) {
  throw new Error('Build the React frontend first: cd frontend; npm ci; npm run build');
}
const backend = new URL(process.env.BACKEND_URL || 'http://127.0.0.1:8081');
const port = Number(process.env.PORT || 8088);
if (!['http:', 'https:'].includes(backend.protocol)) throw new Error('BACKEND_URL must use HTTP(S)');
const mime = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.gif': 'image/gif', '.svg': 'image/svg+xml', '.ico': 'image/x-icon',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf'
};

const server = http.createServer(async (req, res) => {
  if (/^\/api(?:\/|\?|$)/.test(req.url)) {
    const transport = backend.protocol === 'https:' ? https : http;
    const upstream = transport.request({
      protocol: backend.protocol, hostname: backend.hostname, port: backend.port,
      method: req.method, path: req.url.replace(/^\/api(?=\/|\?|$)/, '') || '/',
      headers: { ...req.headers, host: backend.host },
      timeout: 15000
    }, response => {
      res.writeHead(response.statusCode, response.headers);
      response.pipe(res);
      response.on('error', () => res.destroy());
    });
    upstream.on('timeout', () => upstream.destroy(new Error('Backend timeout')));
    upstream.on('error', () => {
      if (res.headersSent) return res.destroy();
      res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ success: false, errorMsg: '服务暂不可用，请稍后重试' }));
    });
    req.on('aborted', () => upstream.destroy());
    req.pipe(upstream);
    return;
  }
  if (!['GET', 'HEAD'].includes(req.method)) {
    res.writeHead(405, { Allow: 'GET, HEAD' });
    res.end();
    return;
  }
  try {
    const pathname = decodeURIComponent(req.url.split('?')[0]);
    let file = path.resolve(root, '.' + (pathname === '/' ? '/index.html' : pathname));
    const relative = path.relative(root, file);
    if (relative.startsWith('..') || path.isAbsolute(relative) || relative.split(/[\\/]/).some(p => p.startsWith('.'))) {
      res.writeHead(403); res.end('Forbidden'); return;
    }
    const exists = await stat(file).catch(() => null);
    if (!exists && (!path.extname(pathname) || /^\/(product|products|order|orders|login)\.html$/.test(pathname))) {
      file = path.join(root, 'index.html');
    }
    const contentType = mime[path.extname(file).toLowerCase()];
    if (!contentType || !(await stat(file)).isFile()) {
      res.writeHead(404); res.end('Not found'); return;
    }
    res.writeHead(200, {
      'Content-Type': contentType, 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff'
    });
    if (req.method === 'HEAD') { res.end(); return; }
    const stream = createReadStream(file);
    stream.on('error', () => res.destroy());
    stream.pipe(res);
  } catch (error) {
    res.writeHead(error instanceof URIError ? 400 : 404);
    res.end('Not found');
  }
});
server.on('error', error => { console.error(error.message); process.exitCode = 1; });
server.listen(port, '127.0.0.1', () => {
  console.log(`LocalJoy: http://127.0.0.1:${port}`);
  console.log(`/api -> ${backend.origin}`);
});
