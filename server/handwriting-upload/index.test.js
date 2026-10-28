'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { validateBatch, validateSample } = require('./index');

function sample(overrides = {}) {
  return {
    format: 'qingyu-hw-trajectory',
    version: 1,
    id: '6f1c2a9e-7b1d-4c55-9a2e-1d4b6c8e9f01',
    label: '永',
    created_at: '2026-10-20',
    canvas: { aspect: 1.0, unit: 'normalized' },
    strokes: [
      [[0.5, 0.2, 0], [0.5, 0.8, 120]],
      [[0.3, 0.4, 200], [0.7, 0.4, 280]],
    ],
    ...overrides,
  };
}

function line(value) {
  return JSON.stringify(value);
}

function id(index) {
  return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`;
}

test('accepts a valid v1 batch', () => {
  const result = validateBatch(`${line(sample())}\n`);
  assert.equal(result.error, undefined);
  assert.equal(result.samples.length, 1);
});

test('accepts supplementary Han labels', () => {
  assert.equal(validateSample(sample({ label: '𠀀' })), null);
});

test('rejects non-Han and multi-character labels', () => {
  assert.equal(validateSample(sample({ label: 'a' })), 'label');
  assert.equal(validateSample(sample({ label: '永a' })), 'label');
});

test('rejects unknown fields', () => {
  assert.equal(validateSample(sample({ device_id: 'x' })), 'unknown_field');
});

test('rejects non-monotonic time', () => {
  const value = sample({
    strokes: [[[0.5, 0.2, 100], [0.5, 0.8, 50]]],
  });
  assert.equal(validateSample(value), 'time');
});

test('rejects out-of-range points', () => {
  const value = sample({
    strokes: [[[1.2, 0.2, 0], [0.5, 0.8, 10]]],
  });
  assert.equal(validateSample(value), 'point');
});

test('rejects duplicate ids in one batch', () => {
  const value = line(sample());
  const result = validateBatch(`${value}\n${value}\n`);
  assert.equal(result.error, 'duplicate_id');
});

test('rejects batches over the sample limit', () => {
  const lines = [];
  for (let index = 0; index < 51; index++) {
    lines.push(line(sample({ id: id(index) })));
  }
  assert.equal(validateBatch(lines.join('\n')).error, 'too_many');
});

test('rejects oversized bodies before parsing', () => {
  assert.equal(validateBatch('x'.repeat(300 * 1024)).error, 'too_large');
});

test('rejects malformed JSON', () => {
  assert.equal(validateBatch('{not-json}\n').error, 'json');
});
