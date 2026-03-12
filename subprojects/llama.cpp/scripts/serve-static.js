const http = require('http');
const fs = require('fs').promises;
const path = require('path');

// This file is used for testing wasm build from emscripten
// Example build command:
// emcmake cmake -B build-wasm -DGGML_WEBGPU=ON -DLLAMA_OPENSSL=OFF
// cmake --build build-wasm --target test-backend-ops -j

const PORT = 8080;
const STATIC_DIR = path.join(__dirname, '../build-wasm/bin');
console.log(`Serving static files from: ${STATIC_DIR}`);

const mimeTypes = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
};

const STATIC_ROOT = path.resolve(STATIC_DIR);
const HTML_SECURITY_HEADERS = {
  'Content-Type': 'text/html',
  'X-Content-Type-Options': 'nosniff',
  'Content-Security-Policy': [
    "default-src 'self'",
    "script-src 'self' 'unsafe-eval'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "worker-src 'self' blob:",
    "connect-src 'self'",
  ].join('; '),
};

function escapeHtml(value) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function resolveRequestPath(reqUrl) {
  const url = new URL(reqUrl, 'http://localhost');
  let pathname = decodeURIComponent(url.pathname);

  if (pathname.includes('\0')) {
    const err = new Error('Invalid path');
    err.code = 'EINVAL';
    throw err;
  }

  pathname = pathname.replace(/\\/g, '/');
  const normalized = path.posix.normalize(pathname);
  const relativePath = normalized.replace(/^\/+/, '');
  const resolvedPath = path.resolve(STATIC_ROOT, relativePath);

  if (resolvedPath !== STATIC_ROOT && !resolvedPath.startsWith(STATIC_ROOT + path.sep)) {
    const err = new Error('Forbidden');
    err.code = 'EACCES';
    throw err;
  }

  return {
    resolvedPath,
    displayPath: '/' + relativePath,
  };
}

function resolveChildPath(parentPath, childName) {
  const resolvedChild = path.resolve(parentPath, childName);

  if (resolvedChild !== parentPath && !resolvedChild.startsWith(parentPath + path.sep)) {
    const err = new Error('Forbidden');
    err.code = 'EACCES';
    throw err;
  }

  return resolvedChild;
}

function assertWithinRoot(resolvedPath) {
  if (resolvedPath !== STATIC_ROOT && !resolvedPath.startsWith(STATIC_ROOT + path.sep)) {
    const err = new Error('Forbidden');
    err.code = 'EACCES';
    throw err;
  }
}

async function generateDirListing(dirPath, reqUrl) {
  const files = await fs.readdir(dirPath);
  let html = `
    <!DOCTYPE html>
    <html>
    <head>
      <title>Directory Listing</title>
      <style>
        body { font-family: Arial, sans-serif; padding: 20px; }
        ul { list-style: none; padding: 0; }
        li { margin: 5px 0; }
        a { text-decoration: none; color: #0066cc; }
        a:hover { text-decoration: underline; }
      </style>
    </head>
    <body>
      <h1>Directory: ${escapeHtml(reqUrl)}</h1>
      <ul>
  `;

  if (reqUrl !== '/') {
    html += `<li><a href="../">../ (Parent Directory)</a></li>`;
  }

  for (const file of files) {
    const filePath = path.join(dirPath, file);
    const stats = await fs.stat(filePath);
    const link = encodeURIComponent(file) + (stats.isDirectory() ? '/' : '');
    const label = `${file}${stats.isDirectory() ? '/' : ''}`;
    html += `<li><a href="${link}">${escapeHtml(label)}</a></li>`;
  }

  html += `
      </ul>
    </body>
    </html>
  `;
  return html;
}

const server = http.createServer(async (req, res) => {
  try {
    // Set COOP and COEP headers
    res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
    res.setHeader('Cross-Origin-Embedder-Policy', 'require-corp');
    res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Expires', '0');

    const { resolvedPath: filePath, displayPath } = resolveRequestPath(req.url);
    const stats = await fs.stat(filePath);

    if (stats.isDirectory()) {
      const indexPath = resolveChildPath(filePath, 'index.html');
      try {
        const indexData = await fs.readFile(indexPath);
        res.writeHeader(200, HTML_SECURITY_HEADERS);
        res.end(indexData);
      } catch {
        // No index.html, generate directory listing
        const dirListing = await generateDirListing(filePath, displayPath);
        res.writeHeader(200, HTML_SECURITY_HEADERS);
        res.end(dirListing);
      }
    } else {
      assertWithinRoot(filePath);
      const ext = path.extname(filePath).toLowerCase();
      const contentType = mimeTypes[ext] || 'application/octet-stream';
      const data = await fs.readFile(filePath);
      if (contentType === 'text/html') {
        res.writeHeader(200, HTML_SECURITY_HEADERS);
      } else {
        res.writeHeader(200, { 'Content-Type': contentType, 'X-Content-Type-Options': 'nosniff' });
      }
      res.end(data);
    }
  } catch (err) {
    if (err.code === 'EACCES') {
      res.writeHeader(403, { 'Content-Type': 'text/plain' });
      res.end('403 Forbidden');
    } else if (err.code === 'ENOENT') {
      res.writeHeader(404, { 'Content-Type': 'text/plain' });
      res.end('404 Not Found');
    } else {
      res.writeHeader(500, { 'Content-Type': 'text/plain' });
      res.end('500 Internal Server Error');
    }
  }
});

server.listen(PORT, () => {
  console.log(`Server running at http://localhost:${PORT}/`);
});
