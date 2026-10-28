'use strict';

const crypto = require('crypto');
const https = require('https');

const MAX_BODY_BYTES = 256 * 1024;
const MAX_SAMPLES = 50;
const MAX_STROKES = 64;
const MAX_POINTS_PER_STROKE = 2048;
const MAX_TOTAL_POINTS = 8192;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const SAMPLE_KEYS = new Set([
  'format', 'version', 'id', 'label', 'created_at', 'canvas', 'device', 'strokes',
]);

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isFiniteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function isHanCodePoint(cp) {
  return (cp >= 0x3400 && cp <= 0x4DBF) ||
    (cp >= 0x4E00 && cp <= 0x9FFF) ||
    (cp >= 0xF900 && cp <= 0xFAFF) ||
    (cp >= 0x20000 && cp <= 0x2FA1F);
}

/** 返回错误码；错误码只描述字段，不回显样本内容。 */
function validateSample(sample) {
  if (!isPlainObject(sample)) return 'sample';
  for (const key of Object.keys(sample)) {
    if (!SAMPLE_KEYS.has(key)) return 'unknown_field';
  }
  if (sample.format !== 'qingyu-hw-trajectory' || sample.version !== 1) return 'format';
  if (typeof sample.id !== 'string' || !UUID_V4.test(sample.id)) return 'id';
  if (typeof sample.label !== 'string' || [...sample.label].length !== 1) return 'label';
  if (!isHanCodePoint(sample.label.codePointAt(0))) return 'label';
  if (sample.created_at !== undefined &&
    (typeof sample.created_at !== 'string' || !DATE.test(sample.created_at))) {
    return 'created_at';
  }

  const canvas = sample.canvas;
  if (!isPlainObject(canvas) || canvas.unit !== 'normalized' ||
    !isFiniteNumber(canvas.aspect) || canvas.aspect <= 0) {
    return 'canvas';
  }
  for (const key of Object.keys(canvas)) {
    if (key !== 'aspect' && key !== 'unit') return 'canvas';
  }

  if (sample.device !== undefined) {
    const device = sample.device;
    if (!isPlainObject(device)) return 'device';
    for (const key of Object.keys(device)) {
      if (!['platform', 'app_version', 'screen_density'].includes(key)) return 'device';
    }
    if (device.platform !== undefined && device.platform !== 'android') return 'device';
    if (device.app_version !== undefined &&
      (typeof device.app_version !== 'string' || device.app_version.length < 1 ||
        device.app_version.length > 32)) {
      return 'device';
    }
    if (device.screen_density !== undefined &&
      (!isFiniteNumber(device.screen_density) || device.screen_density <= 0)) {
      return 'device';
    }
  }

  const strokes = sample.strokes;
  if (!Array.isArray(strokes) || strokes.length < 1 || strokes.length > MAX_STROKES) {
    return 'strokes';
  }
  let totalPoints = 0;
  let lastTime = -1;
  for (const stroke of strokes) {
    if (!Array.isArray(stroke) || stroke.length < 2 ||
      stroke.length > MAX_POINTS_PER_STROKE) {
      return 'stroke';
    }
    for (const point of stroke) {
      if (!Array.isArray(point) || point.length !== 3) return 'point';
      const [x, y, t] = point;
      if (!isFiniteNumber(x) || !isFiniteNumber(y) || !isFiniteNumber(t)) return 'point';
      if (x < 0 || x > 1 || y < 0 || y > 1 || t < 0) return 'point';
      if (t < lastTime) return 'time';
      lastTime = t;
      totalPoints++;
    }
  }
  if (totalPoints > MAX_TOTAL_POINTS) return 'points';
  return null;
}

function validateBatch(body) {
  if (typeof body !== 'string' || body.trim() === '') return { error: 'empty' };
  if (Buffer.byteLength(body, 'utf8') > MAX_BODY_BYTES) return { error: 'too_large' };

  const lines = body.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  if (lines.length === 0) return { error: 'empty' };
  if (lines.length > MAX_SAMPLES) return { error: 'too_many' };

  const ids = new Set();
  const samples = [];
  for (const line of lines) {
    let sample;
    try {
      sample = JSON.parse(line);
    } catch (error) {
      return { error: 'json' };
    }
    const error = validateSample(sample);
    if (error) return { error };
    if (ids.has(sample.id)) return { error: 'duplicate_id' };
    ids.add(sample.id);
    samples.push(sample);
  }
  return { samples };
}

function githubRequest(method, path, token, payload) {
  return new Promise((resolve, reject) => {
    const data = payload === undefined ? null : Buffer.from(payload, 'utf8');
    const headers = {
      'Authorization': `Bearer ${token}`,
      'Accept': 'application/vnd.github+json',
      'User-Agent': 'qingyu-handwriting-upload',
      'X-GitHub-Api-Version': '2022-11-28',
    };
    if (data) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = data.length;
    }
    const request = https.request(
      { hostname: 'api.github.com', port: 443, path, method, headers },
      (response) => {
        let responseBody = '';
        response.setEncoding('utf8');
        response.on('data', (chunk) => {
          if (responseBody.length < 4096) responseBody += chunk;
        });
        response.on('end', () => resolve({
          status: response.statusCode || 0,
          body: responseBody,
        }));
      },
    );
    request.setTimeout(15000, () => request.destroy(new Error('github_timeout')));
    request.on('error', reject);
    if (data) request.write(data);
    request.end();
  });
}

async function commitBatch(body, samples) {
  const token = process.env.GITHUB_TOKEN;
  const owner = process.env.GITHUB_OWNER;
  const repo = process.env.GITHUB_REPO;
  const branch = process.env.GITHUB_BRANCH || 'main';
  if (!token || !owner || !repo) {
    const error = new Error('missing_github_config');
    error.code = 'CONFIG';
    throw error;
  }

  const date = (samples[0].created_at || new Date().toISOString().slice(0, 10))
    .replace(/-/g, '');
  const hash = crypto.createHash('sha256').update(body, 'utf8').digest('hex').slice(0, 32);
  const path = `inbox/${date}/${hash}.jsonl`;
  const apiPath = `/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}` +
    `/contents/${path.split('/').map(encodeURIComponent).join('/')}`;
  const payload = JSON.stringify({
    message: `data: handwriting samples (${samples.length})`,
    content: Buffer.from(body, 'utf8').toString('base64'),
    branch,
  });

  const result = await githubRequest('PUT', apiPath, token, payload);
  if (result.status === 200 || result.status === 201) {
    return { path, duplicate: false };
  }
  if (result.status === 409 || result.status === 422) {
    const existing = await githubRequest('GET', apiPath, token);
    if (existing.status === 200) return { path, duplicate: true };
  }
  const error = new Error(`github_${result.status}`);
  error.code = 'GITHUB';
  throw error;
}

async function main_handler(event) {
  let body = event && event.body;
  if (event && event.isBase64Encoded) {
    body = Buffer.from(body || '', 'base64').toString('utf8');
  }
  const validation = validateBatch(body);
  if (validation.error) {
    const statusCode = validation.error === 'too_large' ? 413 : 400;
    return {
      statusCode,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ok: false, error: validation.error }),
    };
  }

  try {
    const result = await commitBatch(body, validation.samples);
    return {
      statusCode: 200,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ok: true,
        accepted: validation.samples.length,
        path: result.path,
        duplicate: result.duplicate,
      }),
    };
  } catch (error) {
    console.error(`handwriting upload failed: ${error.code || 'unknown'}`);
    return {
      statusCode: error.code === 'CONFIG' ? 500 : 502,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ok: false, error: 'upstream' }),
    };
  }
}

exports.main_handler = main_handler;
exports.validateBatch = validateBatch;
exports.validateSample = validateSample;
