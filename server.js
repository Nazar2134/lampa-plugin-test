'use strict';

const express = require('express');
const cors = require('cors');

const app = express();
const PORT = Number(process.env.PORT) || 3000;
const PROXY_API_KEY = process.env.PROXY_API_KEY || '';
const REQUEST_TIMEOUT_MS = Number(process.env.REQUEST_TIMEOUT_MS) || 25000;

const TORRENTIO_RESOLVE_RE = /^https:\/\/torrentio\.strem\.fun\/resolve\/alldebrid\//i;

app.use(cors());
app.use(express.json({ limit: '32kb' }));

function proxyAuth(req, res, next) {
  if (!PROXY_API_KEY) {
    return next();
  }
  if (req.get('x-proxy-key') === PROXY_API_KEY) {
    return next();
  }
  return res.status(401).json({ ok: false, error: 'Unauthorized' });
}

function validateResolveUrl(url) {
  if (!url || typeof url !== 'string') {
    return { ok: false, error: 'Missing url' };
  }

  const trimmed = url.trim();

  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
      return { ok: false, error: 'Invalid protocol' };
    }
  } catch (e) {
    return { ok: false, error: 'Malformed url' };
  }

  if (!TORRENTIO_RESOLVE_RE.test(trimmed)) {
    return {
      ok: false,
      error: 'Only https://torrentio.strem.fun/resolve/alldebrid/ URLs are allowed'
    };
  }

  return { ok: true, url: trimmed };
}

function logResolveAccepted() {
  console.log('[resolve] request accepted (torrentio/alldebrid path)');
}

async function resolveTorrentioUrl(sourceUrl) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const response = await fetch(sourceUrl, {
      method: 'GET',
      redirect: 'follow',
      signal: controller.signal,
      headers: {
        'User-Agent': 'Lampa-Torrentio-Resolve-Proxy/1.0',
        Accept: '*/*'
      }
    });

    const finalUrl = String(response.url || '').trim();
    const body = await response.text();
    const status = response.status;
    const redirected =
      Boolean(response.redirected) || (Boolean(finalUrl) && finalUrl !== String(sourceUrl).trim());

    console.log('[resolveTorrentioUrl] SOURCE URL:', sourceUrl);
    console.log('[resolveTorrentioUrl] FINAL URL:', finalUrl);
    console.log('[resolveTorrentioUrl] STATUS:', status);
    console.log('[resolveTorrentioUrl] REDIRECTED:', redirected);
    console.log(
      '[resolveTorrentioUrl] BODY (first 2000):',
      body ? body.slice(0, 2000) : ''
    );

    if (finalUrl && !TORRENTIO_RESOLVE_RE.test(finalUrl)) {
      console.log('[resolveTorrentioUrl] host from FINAL URL:', finalUrl);
      return finalUrl;
    }

    const match = body.match(/https?:\/\/[^\s"'<>]+/i);

    if (match && match[0]) {
      console.log('[resolveTorrentioUrl] host from BODY match:', match[0]);
      return match[0];
    }

    console.error('[resolveTorrentioUrl] FAILED — full diagnostic:', {
      sourceUrl,
      finalUrl,
      status,
      redirected,
      bodyLength: body ? body.length : 0,
      bodyPreview: body ? body.slice(0, 2000) : ''
    });

    throw new Error('Upstream did not return a host link');
  } finally {
    clearTimeout(timeoutId);
  }
}

app.get('/health', (req, res) => {
  res.json({ ok: true, service: 'torrentio-resolve-proxy' });
});

async function handleResolve(req, res) {
  const sourceUrl = req.method === 'GET' ? req.query.url : req.body && req.body.url;
  const check = validateResolveUrl(sourceUrl);

  if (!check.ok) {
    return res.status(400).json({ ok: false, error: check.error });
  }

  logResolveAccepted();

  try {
    const hostUrl = await resolveTorrentioUrl(check.url);
    return res.json({ ok: true, url: hostUrl });
  } catch (err) {
    const message = err && err.name === 'AbortError' ? 'Upstream timeout' : err.message || 'Upstream failed';
    return res.status(502).json({ ok: false, error: message });
  }
}

app.get('/resolve', proxyAuth, handleResolve);
app.post('/resolve', proxyAuth, handleResolve);

app.use((req, res) => {
  res.status(404).json({ ok: false, error: 'Not found' });
});

app.listen(PORT, () => {
  console.log('torrentio-resolve-proxy listening on', PORT);
});
