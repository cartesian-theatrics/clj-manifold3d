// Static, loopback-only server. Never evaluates code or exposes the repository.
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');

function createServer() {
  const publicRoot = path.resolve(__dirname, '../public');
  const threeRoot = path.resolve(__dirname, '../node_modules/three');
  return http.createServer(async (request, response) => {
    if (!['GET', 'HEAD'].includes(request.method)) { response.writeHead(405).end(); return; }
    try {
      let name = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
      if (name === '/' || name === '/try-it' || name === '/try-it/') name = '/try-it/index.html';
      const vendor = name.startsWith('/vendor/three/');
      const root = vendor ? threeRoot : publicRoot;
      const relative = vendor ? name.slice('/vendor/three/'.length) : name.slice(1);
      if (!vendor && !/^(try-it\/|wasm\/)/.test(relative)) { response.writeHead(404).end(); return; }
      const file = await fs.realpath(path.resolve(root, relative));
      if (!file.startsWith(root + path.sep)) { response.writeHead(403).end(); return; }
      const mime = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.wasm': 'application/wasm', '.css': 'text/css', '.svg': 'image/svg+xml'};
      response.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream');
      response.setHeader('Cache-Control', 'no-store');
      response.setHeader('X-Content-Type-Options', 'nosniff');
      response.setHeader('Referrer-Policy', 'no-referrer');
      const bytes = await fs.readFile(file);
      response.end(request.method === 'HEAD' ? undefined : bytes);
    } catch (_) { response.writeHead(404).end('Not found'); }
  });
}

module.exports = {createServer};
if (require.main === module) {
  const port = Number(process.env.PORT || 8091);
  const server = createServer();
  server.on('error', error => { console.error(error.message); process.exitCode = 1; });
  server.listen(port, '127.0.0.1', () => console.log(`Manifold Try it! → http://localhost:${port}/`));
}
