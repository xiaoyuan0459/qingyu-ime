#!/usr/bin/env python3
"""Relay an already signed APK without uploading its private key or full payload.

The delta contains public APK bytes plus ranges copied from the exact Actions
input. It cannot sign another build. Use SignRelease.java locally first.
"""
import argparse
import base64
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile

SIGNATURE = re.compile(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))", re.I)
MAX_APK = 256 * 1024 * 1024
MAX_DELTA = 4 * 1024 * 1024


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def payload(data):
    result = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or SIGNATURE.fullmatch(entry.filename):
                continue
            if entry.filename in result:
                raise ValueError("Duplicate APK payload entry")
            result[entry.filename] = sha256(archive.read(entry))
    return result


def span(data, entry):
    offset = entry.header_offset
    if data[offset:offset + 4] != b"PK\x03\x04":
        raise ValueError("Invalid ZIP local header")
    name, extra = struct.unpack_from("<HH", data, offset + 26)
    return offset + 30 + name + extra, entry.compress_size


def write_new(path, data):
    with path.open("xb") as stream:
        stream.write(data)


def create(unsigned, signed, output):
    source, target = unsigned.read_bytes(), signed.read_bytes()
    if max(len(source), len(target)) > MAX_APK:
        raise ValueError("APK exceeds relay size limit")
    if payload(source) != payload(target):
        raise ValueError("Signing changed the Actions APK payload")
    ranges = []
    with zipfile.ZipFile(io.BytesIO(source)) as old, zipfile.ZipFile(io.BytesIO(target)) as new:
        for entry in new.infolist():
            try:
                original = old.getinfo(entry.filename)
            except KeyError:
                continue
            start, size = span(source, original)
            dest, length = span(target, entry)
            if size and size == length and source[start:start + size] == target[dest:dest + size]:
                ranges.append((dest, start, size))
    ops, cursor = [], 0
    for dest, start, size in sorted(ranges):
        if dest < cursor:
            raise ValueError("Overlapping ZIP entries")
        if dest > cursor:
            ops.append(base64.b64encode(target[cursor:dest]).decode("ascii"))
        ops.append([start, size])
        cursor = dest + size
    if cursor < len(target):
        ops.append(base64.b64encode(target[cursor:]).decode("ascii"))
    delta = {"format": 1, "unsigned_sha256": sha256(source), "signed_sha256": sha256(target),
             "signed_size": len(target), "ops": ops}
    encoded = gzip.compress(json.dumps(delta, separators=(",", ":")).encode("ascii"), mtime=0)
    if len(encoded) > MAX_DELTA:
        raise ValueError("Delta exceeds relay size limit")
    write_new(output, encoded)
    print(json.dumps({"signed_sha256": sha256(target), "delta_bytes": len(encoded)}))


def restore(unsigned, delta_path, output, expected):
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("Expected signed SHA-256 must be supplied independently")
    if output.exists():
        raise ValueError("Output exists; choose a new output path")
    if unsigned.stat().st_size > MAX_APK or delta_path.stat().st_size > MAX_DELTA:
        raise ValueError("Relay input exceeds size limit")
    source = unsigned.read_bytes()
    with gzip.open(delta_path, "rb") as stream:
        raw = stream.read(16 * 1024 * 1024 + 1)
    if len(raw) > 16 * 1024 * 1024:
        raise ValueError("Expanded delta exceeds size limit")
    delta = json.loads(raw)
    if delta.get("format") != 1 or delta.get("unsigned_sha256") != sha256(source):
        raise ValueError("Delta does not match the Actions unsigned APK")
    if delta.get("signed_sha256") != expected:
        raise ValueError("Delta does not match the expected signed APK")
    size, ops = delta.get("signed_size"), delta.get("ops")
    if type(size) is not int or not 0 < size <= MAX_APK or not isinstance(ops, list) or len(ops) > 10000:
        raise ValueError("Invalid delta output size or operations")
    target = bytearray()
    for op in ops:
        if isinstance(op, list) and len(op) == 2 and all(type(x) is int for x in op):
            start, count = op
            if start < 0 or count <= 0 or start + count > len(source):
                raise ValueError("Invalid source range")
            part = source[start:start + count]
        elif isinstance(op, str):
            part = base64.b64decode(op, validate=True)
        else:
            raise ValueError("Invalid delta operation")
        if len(target) + len(part) > size:
            raise ValueError("Delta exceeds declared output size")
        target.extend(part)
    if len(target) != size or sha256(target) != expected:
        raise ValueError("Reconstructed APK SHA-256 mismatch")
    if payload(source) != payload(target):
        raise ValueError("Delta changed the Actions APK payload")
    write_new(output, target)
    print(json.dumps({"signed_sha256": expected, "bytes": size}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    make = commands.add_parser("create", help="Prepare public signing data after local signing")
    make.add_argument("unsigned", type=Path)
    make.add_argument("signed", type=Path)
    make.add_argument("output", type=Path)
    unpack = commands.add_parser("restore", help="Restore and verify an exact locally signed APK")
    unpack.add_argument("unsigned", type=Path)
    unpack.add_argument("delta", type=Path)
    unpack.add_argument("output", type=Path)
    unpack.add_argument("--sha256", required=True)
    args = parser.parse_args()
    try:
        if args.command == "create":
            create(args.unsigned, args.signed, args.output)
        else:
            restore(args.unsigned, args.delta, args.output, args.sha256)
    except (OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
