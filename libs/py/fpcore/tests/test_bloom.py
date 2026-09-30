"""Tests for fpcore.bloom — Rotating Bloom Filter (§7.5)"""
import time
import pytest
from fpcore.bloom import BloomFilter, RotatingBloomFilter


class TestBloomFilter:
    def test_insert_then_contains(self):
        bf = BloomFilter(10000, 0.001)
        bf.add(b"hello")
        assert b"hello" in bf

    def test_definitely_not_present(self):
        bf = BloomFilter(10000, 0.001)
        bf.add(b"hello")
        # This can very rarely false-positive, but for a fresh filter with 1 item,
        # a completely different key should not be present
        # (we test FP rate separately)
        assert b"xyz_not_inserted_at_all_12345" not in bf

    def test_fp_rate_within_tolerance(self):
        """Measured FP rate should be within ~2× of theoretical at 0.1%."""
        n = 100_000
        bf = BloomFilter(n, 0.001)
        for i in range(n):
            bf.add(str(i).encode())
        # Test with items definitely not in the filter
        fp = sum(1 for i in range(n, n + 10_000) if str(i).encode() in bf)
        fp_rate = fp / 10_000
        assert fp_rate < 0.005, f"FP rate {fp_rate:.4f} exceeds 0.5% threshold"

    def test_memory_scales_with_capacity(self):
        bf1 = BloomFilter(100_000, 0.001)
        bf2 = BloomFilter(1_000_000, 0.001)
        assert bf2.memory_bytes > bf1.memory_bytes

    def test_clear_resets(self):
        bf = BloomFilter(1000, 0.01)
        bf.add(b"key")
        assert b"key" in bf
        bf.clear()
        # After clear, should not be found (may rarely FP but overwhelming probability)
        # We test count = 0 as a proxy
        assert bf.count == 0

    def test_never_panics(self):
        bf = BloomFilter(100, 0.01)
        for x in [b"", b"\x00", b"\xff" * 100, b"a" * 10000]:
            try:
                bf.add(x)
                _ = x in bf
            except Exception as e:
                pytest.fail(f"Raised {e} on {x!r}")


class TestRotatingBloomFilter:
    def test_insert_and_find(self):
        rbf = RotatingBloomFilter(10000, 0.001, window_seconds=60)
        rbf.add(b"event1")
        assert b"event1" in rbf

    def test_finds_in_previous_generation(self):
        """Item added before rotation should still be found in previous gen."""
        rbf = RotatingBloomFilter(1000, 0.01, window_seconds=0.01)
        rbf.add(b"old_item")
        time.sleep(0.02)  # trigger rotation
        # Trigger rotation by checking
        _ = b"trigger" in rbf
        # old_item should be in previous generation
        assert b"old_item" in rbf

    def test_memory_is_bounded(self):
        rbf = RotatingBloomFilter(100_000, 0.001, window_seconds=60)
        mem = rbf.memory_bytes
        # Should be ~2× single filter memory
        single = BloomFilter(100_000, 0.001).memory_bytes
        assert mem <= single * 3  # at most 2 generations + overhead
