"""Public CLI contracts for transporting already signed APK bytes."""
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

TOOL = Path(__file__).with_name("signed-apk-delta.py")


class SignedApkDeltaTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.unsigned = self.root / "unsigned.apk"
        self.signed = self.root / "signed.apk"
        self.delta = self.root / "delta.gz"
        self.output = self.root / "restored.apk"
        for path in (self.unsigned, self.signed):
            with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("classes.dex", b"synthetic bytecode" * 100)
                archive.writestr("assets/license.txt", b"synthetic license")
                if path == self.signed:
                    archive.writestr("META-INF/CERT.SF", b"synthetic signature metadata")
        self.expected = hashlib.sha256(self.signed.read_bytes()).hexdigest()

    def call(self, *args):
        return subprocess.run([sys.executable, "-B", str(TOOL), *map(str, args)],
                              capture_output=True, encoding="utf-8")

    def test_roundtrip_preserves_exact_signed_archive_bytes(self):
        self.assertEqual(self.call("create", self.unsigned, self.signed, self.delta).returncode, 0)
        result = self.call("restore", self.unsigned, self.delta, self.output, "--sha256", self.expected)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.output.read_bytes(), self.signed.read_bytes())

    def test_changed_program_payload_is_rejected_before_upload(self):
        with zipfile.ZipFile(self.signed, "a") as archive:
            archive.writestr("assets/unexpected.txt", b"unexpected runtime data")
        result = self.call("create", self.unsigned, self.signed, self.delta)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("changed the Actions APK payload", result.stderr)
        self.assertFalse(self.delta.exists())

    def test_restore_rejects_other_build_or_other_signed_hash_without_output(self):
        self.assertEqual(self.call("create", self.unsigned, self.signed, self.delta).returncode, 0)
        for mode in ("signed hash", "unsigned input"):
            with self.subTest(mode=mode):
                expected = "0" * 64 if mode == "signed hash" else self.expected
                if mode == "unsigned input":
                    with zipfile.ZipFile(self.unsigned, "a") as archive:
                        archive.writestr("assets/other-build.txt", b"other build")
                result = self.call("restore", self.unsigned, self.delta, self.output, "--sha256", expected)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("does not match", result.stderr)
                self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
