"""Benchmark safety/measurement regression tests; no Java or Minecraft process is started."""

import contextlib
from collections import deque
import gzip
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile
import zlib


SPEC = importlib.util.spec_from_file_location("benchmark_server", Path(__file__).with_name("benchmark-server.py"))
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)


def string(value):
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def tag(kind, name, payload):
    return bytes([kind]) + string(name) + payload


def chunk_nbt(status="minecraft:full", prefix=b"", suffix=b""):
    return b"\x0a\x00\x00" + prefix + tag(8, "Status", string(status)) + suffix + b"\x00"


def write_chunks(world, chunks, payload=None, compression=2, external=False):
    """Write minimal valid region headers and chunk records without Minecraft libraries."""
    payload = chunk_nbt() if payload is None else payload
    encoded = {1: gzip.compress, 2: zlib.compress, 3: lambda data: data}[compression](payload)
    regions = {}
    for x, z in chunks:
        path, offset = benchmark.region_location(world, x, z)
        regions.setdefault(path, []).append((x, z, offset))
    for path, entries in regions.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        data = bytearray(8192)
        for x, z, offset in entries:
            record_payload = b"" if external else encoded
            record = struct.pack(">IB", len(record_payload) + 1, compression | (128 if external else 0)) + record_payload
            sectors = (len(record) + 4095) // 4096
            data[offset:offset + 4] = (len(data) // 4096).to_bytes(3, "big") + bytes([sectors])
            data.extend(record + b"\0" * (sectors * 4096 - len(record)))
            if external:
                (path.parent / f"c.{x}.{z}.mcc").write_bytes(encoded)
        path.write_bytes(data)


def write_mod(path, *ids):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as jar:
        jar.writestr("META-INF/mods.toml", "\n".join(f'[[mods]]\nmodId="{mod_id}"' for mod_id in ids))


class TemporaryBenchmark(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="t2me-benchmark-unittest-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.world = self.root / "world"
        self.world.mkdir()

    def make_args(self, output="results"):
        template = self.root / "template"
        template.mkdir(exist_ok=True)
        (template / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (template / "server.properties").write_text(
            "level-name=original-world\nserver-port=25565\nlevel-seed=12345\nview-distance=8\n",
            encoding="utf-8")
        (self.world / "level.dat").write_bytes(b"fixed synthetic seed fixture")
        jars = {name: self.root / f"selected-{name}.jar" for name in ("t2me", "chunky")}
        for name, path in jars.items():
            write_mod(path, name)
        return SimpleNamespace(server_template=template, world_template=self.world,
                               t2me_jar=jars["t2me"], chunky_jar=jars["chunky"],
                               output=self.root / output, center_x=8192, center_z=8192,
                               radius=32, repeats=3, chunky_world="minecraft:overworld",
                               startup_timeout=1, generation_timeout=1, shutdown_timeout=1,
                               settle_seconds=0, command=["java", "-Xmx4G", "@forge_args.txt", "nogui"])


class RegionVerificationTests(TemporaryBenchmark):
    def test_default_selection_and_negative_region_coordinates(self):
        chunks = benchmark.coordinates(SimpleNamespace(center_x=8192, center_z=8192, radius=128))
        self.assertEqual(289, len(chunks))
        self.assertEqual(set(range(504, 521)), {x for x, _ in chunks})
        self.assertEqual(set(range(504, 521)), {z for _, z in chunks})
        path, offset = benchmark.region_location(self.world, -1, -33)
        self.assertEqual("r.-1.-2.mca", path.name)
        self.assertEqual(4092, offset)

    def test_all_supported_compressions_and_external_records(self):
        chunks = [(-1, -33), (0, 0), (31, 31), (32, 32)]
        for compression in (1, 2, 3):
            for external in (False, True):
                with self.subTest(compression=compression, external=external):
                    write_chunks(self.world, chunks, compression=compression, external=external)
                    self.assertEqual(4, benchmark.verify_full_chunks(self.world, chunks))

    def test_existing_chunks_including_protochunks_are_rejected(self):
        benchmark.ensure_ungenerated(self.world, [(0, 0)])
        write_chunks(self.world, [(0, 0)], chunk_nbt("minecraft:noise"))
        with self.assertRaisesRegex(benchmark.BenchmarkError, "already exists"):
            benchmark.ensure_ungenerated(self.world, [(0, 0)])
        benchmark.ensure_ungenerated(self.world, [(1, 0)])

    def test_missing_wrong_status_and_missing_status_are_rejected(self):
        with self.assertRaisesRegex(benchmark.BenchmarkError, "Missing region"):
            benchmark.verify_full_chunks(self.world, [(0, 0)])
        for payload in (chunk_nbt("minecraft:features"), b"\x0a\x00\x00\x00"):
            with self.subTest(payload=payload):
                write_chunks(self.world, [(0, 0)], payload)
                with self.assertRaisesRegex(benchmark.BenchmarkError, "not FULL"):
                    benchmark.verify_full_chunks(self.world, [(0, 0)])
        with self.assertRaisesRegex(benchmark.BenchmarkError, "was not saved"):
            benchmark.verify_full_chunks(self.world, [(1, 0)])

    def test_truncated_and_invalid_region_records_are_rejected(self):
        path, _ = benchmark.region_location(self.world, 0, 0)
        path.parent.mkdir()
        path.write_bytes(b"\0")
        with self.assertRaisesRegex(benchmark.BenchmarkError, "Truncated region header"):
            benchmark.ensure_ungenerated(self.world, [(0, 0)])
        for description, mutation in (
                ("invalid sector", lambda data: data.__setitem__(slice(0, 4), b"\0\0\x01\x01")),
                ("invalid size", lambda data: data.__setitem__(slice(8192, 8196), struct.pack(">I", 4097))),
                ("unsupported compression", lambda data: data.__setitem__(8196, 4)),
                ("truncated record", lambda data: data.__delitem__(slice(8195, None))),
                ("truncated payload", lambda data: data.__delitem__(slice(8200, None)))):
            with self.subTest(description=description):
                write_chunks(self.world, [(0, 0)])
                data = bytearray(path.read_bytes())
                mutation(data)
                path.write_bytes(data)
                with self.assertRaises(benchmark.BenchmarkError):
                    benchmark.verify_full_chunks(self.world, [(0, 0)])

    def test_nested_and_non_status_tags_are_skipped_correctly(self):
        prefix = b"".join(tag(kind, f"number{kind}", b"\0" * width)
                          for kind, width in ((1, 1), (2, 2), (3, 4), (4, 8), (5, 4), (6, 8)))
        prefix += tag(7, "bytes", struct.pack(">i", 3) + b"abc")
        prefix += tag(11, "ints", struct.pack(">iii", 2, 10, -10))
        prefix += tag(12, "longs", struct.pack(">iq", 1, 100))
        prefix += tag(8, "modifiedUtf8", b"\0\x02\xc0\x80")
        prefix += tag(9, "children", b"\x0a" + struct.pack(">i", 1)
                      + tag(8, "Status", string("noise")) + b"\0")
        prefix += tag(10, "nested", tag(8, "Status", string("empty")) + b"\0")
        payload = chunk_nbt(prefix=prefix, suffix=tag(3, "afterStatus", struct.pack(">i", 7)))
        self.assertEqual("minecraft:full", benchmark.nbt_status(payload))
        write_chunks(self.world, [(0, 0)], payload)
        self.assertEqual(1, benchmark.verify_full_chunks(self.world, [(0, 0)]))

    def test_full_status_does_not_mask_truncated_or_malformed_nbt(self):
        invalid = [b"\x08\x00\x00", chunk_nbt()[:-1], chunk_nbt() + b"extra",
                   chunk_nbt(suffix=tag(11, "array", struct.pack(">i", -1))),
                   chunk_nbt(suffix=tag(9, "list", b"\x03" + struct.pack(">i", -1))),
                   chunk_nbt(suffix=tag(99, "unsupported", b"")),
                   chunk_nbt(suffix=tag(8, "truncated", b"\x00\xffabc"))]
        for payload in invalid:
            with self.subTest(payload=payload):
                write_chunks(self.world, [(0, 0)], payload)
                with self.assertRaises(benchmark.BenchmarkError):
                    benchmark.verify_full_chunks(self.world, [(0, 0)])


class TemplateIsolationTests(TemporaryBenchmark):
    def test_candidate_identity_and_configs_preserved_without_touching_sources(self):
        args = self.make_args()
        mods = args.server_template / "mods"
        write_mod(mods / "unrecognizable-one.jar", "t2me")
        write_mod(mods / "unrecognizable-two.jar", "chunky")
        write_mod(mods / "terrain.jar", "shared_worldgen")
        config = args.server_template / "config" / "shared.toml"
        config.parent.mkdir()
        config.write_text("worldgen_mode = 'same'\n", encoding="utf-8")
        server_config = self.world / "serverconfig" / "t2me-server.toml"
        server_config.parent.mkdir()
        server_config.write_text("[scheduler]\nmaxInFlight=32\n", encoding="utf-8")
        (self.world / "session.lock").write_bytes(b"lock")
        old_world = args.server_template / "original-world"
        old_world.mkdir()
        (old_world / "level.dat").write_bytes(b"must not use this world")
        original_properties = (args.server_template / "server.properties").read_bytes()

        for candidate in ("t2me", "chunky"):
            with self.subTest(candidate=candidate):
                run = self.root / candidate
                run.mkdir()
                copied = benchmark.prepare_server(args, run, candidate)
                ids = set().union(*(benchmark.mod_ids(jar) for jar in (copied / "mods").glob("*.jar")))
                self.assertEqual({candidate, "shared_worldgen"}, ids)
                self.assertFalse((copied / "original-world").exists())
                self.assertEqual((self.world / "level.dat").read_bytes(),
                                 (copied / "benchmark-world" / "level.dat").read_bytes())
                self.assertFalse((copied / "benchmark-world" / "session.lock").exists())
                self.assertEqual(config.read_bytes(), (copied / "config" / "shared.toml").read_bytes())
                self.assertEqual(server_config.read_bytes(),
                                 (copied / "benchmark-world" / "serverconfig" / server_config.name).read_bytes())
                properties = (copied / "server.properties").read_text(encoding="utf-8")
                for expected in ("level-name=benchmark-world", "server-ip=127.0.0.1", "server-port=0",
                                 "enable-rcon=false", "level-seed=12345", "view-distance=8"):
                    self.assertIn(expected, properties)
        self.assertEqual(original_properties, (args.server_template / "server.properties").read_bytes())
        self.assertEqual(3, len(list(mods.glob("*.jar"))))
        self.assertTrue((self.world / "session.lock").exists())


class RunVerificationTests(TemporaryBenchmark):
    def run_with_console(self, candidate, lines, save_on_flush=True, save_on_stop=False):
        args = self.make_args()
        args.output.mkdir()
        chunks = benchmark.coordinates(args)
        events, commands = [], []

        class FakeConsole:
            def __init__(self, command, directory, run):
                self.world = directory / "benchmark-world"
                self.lines = deque(lines)
                self.flushes = 0

            def send(self, command):
                commands.append(command)
                if command == "save-all flush":
                    self.flushes += 1
                    if self.flushes == 2 and save_on_flush:
                        write_chunks(self.world, chunks)

            def wait(self, pattern, timeout):
                return 15.0

            def barrier(self, timeout):
                pass

            def next_line(self, deadline):
                if not self.lines:
                    raise benchmark.BenchmarkError("Timeout: no recognized completion")
                return 12.0, self.lines.popleft()

            def stop(self, timeout):
                events.append("stop")
                if save_on_stop:
                    write_chunks(self.world, chunks)
                return True

        actual_verify = benchmark.verify_full_chunks

        def checked_verify(world, selected):
            events.append("verify")
            return actual_verify(world, selected)

        with patch.object(benchmark, "Server", FakeConsole), \
                patch.object(benchmark.time, "perf_counter", return_value=10.0), \
                patch.object(benchmark, "verify_full_chunks", side_effect=checked_verify):
            result = benchmark.run_candidate(args, candidate, 1, chunks)
        return result, events, commands

    def test_success_records_completion_and_flush_and_verifies_before_stop(self):
        result, events, commands = self.run_with_console(
            "t2me", ["Completed T2ME job=id state=completed done=25/25 failures=0"])
        self.assertTrue(result["success"], result)
        self.assertEqual(2.0, result["completion_seconds"])
        self.assertEqual(5.0, result["completion_and_flush_seconds"])
        self.assertEqual(25, result["verified_full_chunks"])
        self.assertEqual(["verify", "stop"], events)
        self.assertEqual(2, commands.count("save-all flush"))
        self.assertIn("t2me pregen startat minecraft:overworld 8192 8192 32 square", commands)

    def test_chunky_completion_and_exact_corners(self):
        result, _, commands = self.run_with_console(
            "chunky", ["[Chunky] Task finished for minecraft:overworld. Processed: 25 chunks (100.00%), Total time: 0:00:02"])
        self.assertTrue(result["success"], result)
        self.assertIn("chunky corners 8160 8160 8224 8224", commands)
        self.assertIn("chunky world minecraft:overworld", commands)

    def test_target_mismatch_is_rejected_and_stopped(self):
        result, events, _ = self.run_with_console(
            "t2me", ["T2ME job=id state=completed done=26/26"])
        self.assertFalse(result["success"])
        self.assertIn("target mismatch", result["error"])
        self.assertEqual(["stop"], events)

    def test_chunky_count_mismatch_is_rejected(self):
        result, events, _ = self.run_with_console(
            "chunky", ["Task finished for minecraft:overworld. Processed: 26 chunks (100.00%)"])
        self.assertFalse(result["success"])
        self.assertIn("Completed count mismatch", result["error"])
        self.assertEqual(["stop"], events)

    def test_unknown_completion_format_fails_closed(self):
        result, events, _ = self.run_with_console("chunky", ["A different message format claims success"])
        self.assertFalse(result["success"])
        self.assertIn("no recognized completion", result["error"])
        self.assertEqual(["stop"], events)

    def test_shutdown_save_cannot_hide_incomplete_timed_flush(self):
        result, events, _ = self.run_with_console(
            "t2me", ["T2ME job=id state=completed done=25/25"], save_on_flush=False, save_on_stop=True)
        self.assertFalse(result["success"])
        self.assertIn("Missing region", result["error"])
        self.assertEqual(["verify", "stop"], events)
        self.assertTrue(result["clean_shutdown"])


class ComparisonReportTests(TemporaryBenchmark):
    def invoke_main(self, args, result_factory):
        with patch.object(benchmark, "parse_args", return_value=args), \
                patch.object(benchmark, "run_candidate", side_effect=result_factory) as run, \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = benchmark.main()
        return code, run

    @staticmethod
    def successful_result(args, candidate, number, chunks):
        seconds = 2.0 if candidate == "t2me" else 4.0
        return {"candidate": candidate, "success": True, "reported_chunks": len(chunks),
                "verified_full_chunks": len(chunks), "completion_seconds": seconds,
                "completion_and_flush_seconds": seconds + 1.0}

    def test_alternating_pairs_and_median_report(self):
        args = self.make_args()
        code, calls = self.invoke_main(args, self.successful_result)
        self.assertEqual(0, code)
        self.assertEqual(["t2me", "chunky", "chunky", "t2me", "t2me", "chunky"],
                         [call.args[1] for call in calls.call_args_list])
        report = json.loads((args.output / "report.json").read_text(encoding="utf-8"))
        self.assertTrue(report["comparable"])
        self.assertEqual(2.0, report["chunky_time_divided_by_t2me_time"]["completion_seconds"])
        self.assertEqual(25, report["selection"]["expected_chunks"])
        self.assertEqual(benchmark.sha256(args.world_template / "level.dat"), report["world_level_dat_sha256"])

    def test_failed_run_prevents_partial_speed_claims(self):
        args = self.make_args()

        def results(*arguments):
            if arguments[2] == 2:
                return {"candidate": arguments[1], "success": False, "error": "count mismatch"}
            return self.successful_result(*arguments)

        code, calls = self.invoke_main(args, results)
        self.assertEqual(1, code)
        self.assertEqual(2, calls.call_count)
        report = json.loads((args.output / "report.json").read_text(encoding="utf-8"))
        self.assertFalse(report["comparable"])
        self.assertNotIn("medians", report)
        self.assertNotIn("chunky_time_divided_by_t2me_time", report)

    def test_existing_output_is_rejected_without_launching(self):
        args = self.make_args()
        args.output.mkdir()
        sentinel = args.output / "keep.txt"
        sentinel.write_text("existing results", encoding="utf-8")
        with self.assertRaisesRegex(benchmark.BenchmarkError, "new directory"):
            self.invoke_main(args, lambda *_: self.fail("must not launch"))
        self.assertEqual("existing results", sentinel.read_text(encoding="utf-8"))

    def test_unaligned_chunky_radius_is_rejected_before_comparison(self):
        args = self.make_args()
        for radius in (31, 127, 129, 2049):
            with self.subTest(radius=radius):
                args.radius = radius
                args.output = self.root / f"invalid-radius-{radius}"
                with self.assertRaises(benchmark.BenchmarkError):
                    self.invoke_main(args, lambda *_: self.fail("unaligned radius must not launch"))
                self.assertFalse(args.output.exists())


if __name__ == "__main__":
    unittest.main()
