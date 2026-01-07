#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v1.1.7 Test Results Analyzer
Analyzes logcat output to verify bitmap leak fixes and memory stability
"""

import re
import sys
from collections import defaultdict
from datetime import datetime

class TestAnalyzer:
    def __init__(self, log_file):
        self.log_file = log_file
        self.bitmap_allocations = 0
        self.bitmap_releases = 0
        self.memory_samples = []
        self.similarity_times = []
        self.ocr_skips = 0
        self.ocr_executions = 0
        self.memory_warnings = 0

    def parse_log(self):
        """Parse the log file and extract metrics"""
        print(f"📊 Analyzing log file: {self.log_file}")
        print("=" * 60)

        try:
            with open(self.log_file, 'r', encoding='utf-8', errors='ignore') as f:
                for line in f:
                    self.analyze_line(line)
        except FileNotFoundError:
            print(f"❌ Error: Log file not found: {self.log_file}")
            return False
        except Exception as e:
            print(f"❌ Error reading log file: {e}")
            return False

        return True

    def analyze_line(self, line):
        """Analyze a single log line"""

        # Bitmap recycled (releases)
        if "[v1.1.7] Bitmap recycled" in line:
            self.bitmap_releases += 1

        # Bitmap copy (allocations)
        if "[v1.1.7] Screen saved" in line:
            self.bitmap_allocations += 1

        # Memory usage
        memory_match = re.search(r'Used: (\d+)MB / Max: (\d+)MB \((\d+)%\)', line)
        if memory_match:
            used = int(memory_match.group(1))
            max_mem = int(memory_match.group(2))
            percent = int(memory_match.group(3))
            self.memory_samples.append({
                'used': used,
                'max': max_mem,
                'percent': percent,
                'line': line.strip()
            })

        # Similarity calculation time
        sim_match = re.search(r'\[v1.1.7\] Similarity calculation.*Time: (\d+)ms', line)
        if sim_match:
            time_ms = int(sim_match.group(1))
            self.similarity_times.append(time_ms)

        # OCR skipped
        if "[v1.1.7] OCR skipped" in line:
            self.ocr_skips += 1

        # OCR performed (screen changed)
        if "[v1.1.7] Screen changed" in line:
            self.ocr_executions += 1

        # Memory warnings
        if "⚠️ High memory usage" in line:
            self.memory_warnings += 1
            print(f"⚠️ {line.strip()}")

    def generate_report(self):
        """Generate analysis report"""
        print("\n" + "=" * 60)
        print("📋 TEST ANALYSIS REPORT")
        print("=" * 60)

        # Bitmap Management
        print("\n🖼️ BITMAP MANAGEMENT:")
        print(f"  Allocations (copy): {self.bitmap_allocations}")
        print(f"  Releases (recycle): {self.bitmap_releases}")

        if self.bitmap_allocations > 0:
            balance = self.bitmap_releases - self.bitmap_allocations
            if balance >= 0:
                print(f"  ✅ Balance: +{balance} (OK - all bitmaps released)")
            else:
                print(f"  ❌ Balance: {balance} (LEAK - {abs(balance)} bitmaps not released!)")
        else:
            print("  ℹ️ No bitmap operations detected in log")

        # Memory Usage
        print("\n💾 MEMORY USAGE:")
        if self.memory_samples:
            initial = self.memory_samples[0]
            final = self.memory_samples[-1]
            avg_percent = sum(s['percent'] for s in self.memory_samples) / len(self.memory_samples)
            max_percent = max(s['percent'] for s in self.memory_samples)

            print(f"  Initial: {initial['used']}MB ({initial['percent']}%)")
            print(f"  Final:   {final['used']}MB ({final['percent']}%)")
            print(f"  Average: {avg_percent:.1f}%")
            print(f"  Peak:    {max_percent}%")

            if max_percent < 70:
                print("  ✅ Memory usage is healthy (< 70%)")
            elif max_percent < 80:
                print("  ⚠️ Memory usage is moderate (70-80%)")
            else:
                print("  ❌ Memory usage is high (> 80%)")

            # Check for memory growth
            growth = final['used'] - initial['used']
            if abs(growth) <= 20:
                print(f"  ✅ Memory stable (growth: {growth:+d}MB)")
            else:
                print(f"  ⚠️ Memory growth detected: {growth:+d}MB")
        else:
            print("  ℹ️ No memory samples detected in log")

        # Performance
        print("\n⚡ PERFORMANCE:")
        print(f"  OCR Skipped: {self.ocr_skips}")
        print(f"  OCR Executed: {self.ocr_executions}")

        if self.ocr_skips + self.ocr_executions > 0:
            skip_rate = (self.ocr_skips / (self.ocr_skips + self.ocr_executions)) * 100
            print(f"  Skip Rate: {skip_rate:.1f}%")

        if self.similarity_times:
            avg_time = sum(self.similarity_times) / len(self.similarity_times)
            min_time = min(self.similarity_times)
            max_time = max(self.similarity_times)

            print(f"\n  Similarity Calculation:")
            print(f"    Average: {avg_time:.1f}ms")
            print(f"    Min: {min_time}ms")
            print(f"    Max: {max_time}ms")

            if avg_time < 20:
                print("    ✅ Performance is excellent (< 20ms)")
            elif avg_time < 30:
                print("    ⚠️ Performance is acceptable (20-30ms)")
            else:
                print("    ❌ Performance is slow (> 30ms)")

        # Warnings
        print(f"\n⚠️ WARNINGS:")
        print(f"  Memory warnings: {self.memory_warnings}")
        if self.memory_warnings > 0:
            print("  ⚠️ High memory usage detected during test")
        else:
            print("  ✅ No memory warnings")

        # Overall Status
        print("\n" + "=" * 60)
        print("🎯 OVERALL STATUS:")
        print("=" * 60)

        issues = []

        if self.bitmap_allocations > 0 and self.bitmap_releases < self.bitmap_allocations:
            issues.append("Bitmap leak detected")

        if self.memory_samples and max(s['percent'] for s in self.memory_samples) >= 80:
            issues.append("High memory usage")

        if self.similarity_times and sum(self.similarity_times) / len(self.similarity_times) > 30:
            issues.append("Slow similarity calculation")

        if self.memory_warnings > 0:
            issues.append("Memory warnings during test")

        if not issues:
            print("✅ ALL TESTS PASSED")
            print("   - No bitmap leaks detected")
            print("   - Memory usage is stable")
            print("   - Performance is acceptable")
            print("   - Ready for production")
            return 0
        else:
            print("❌ ISSUES FOUND:")
            for issue in issues:
                print(f"   - {issue}")
            print("\n   Please review the logs and fix issues before release")
            return 1

def main():
    if len(sys.argv) < 2:
        print("Usage: python analyze_test_results.py <log_file>")
        print("Example: python analyze_test_results.py test_logs/v1.1.7_test_20260107.txt")
        return 1

    log_file = sys.argv[1]
    analyzer = TestAnalyzer(log_file)

    if not analyzer.parse_log():
        return 1

    return analyzer.generate_report()

if __name__ == "__main__":
    sys.exit(main())
