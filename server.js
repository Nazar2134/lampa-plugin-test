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

    console.log('================================');
    console.log('TORRENTIO DEBUG');
    console.log('SOURCE URL:', sourceUrl);
    console.log('FINAL URL:', response.url);
    console.log('STATUS:', response.status);
    console.log('REDIRECTED:', response.redirected);

    const body = await response.text();

    console.log('BODY START');
    console.log(body.substring(0, 2000));
    console.log('BODY END');
    console.log('================================');

    const finalUrl = String(response.url || '').trim();

    if (
      finalUrl &&
      !TORRENTIO_RESOLVE_RE.test(finalUrl)
    ) {
      return finalUrl;
    }

    const match = body.match(/https?:\/\/[^\s"'<>]+/i);

    if (match && match[0]) {
      return match[0];
    }

    throw new Error('Upstream did not return a host link');
  }
  finally {
    clearTimeout(timeoutId);
  }
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
