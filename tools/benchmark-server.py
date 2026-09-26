#!/usr/bin/env python3
"""Compare prepared Forge servers on fresh world copies. Python 3.11+, stdlib only."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import queue
import re
import shutil
import stat
import statistics
import struct
import subprocess
import sys
import threading
import time
import tomllib
import uuid
import zipfile
import zlib


class BenchmarkError(RuntimeError):
    pass


def normalize_console_line(line: str) -> str:
    return re.sub(r"\x1b\[[0-9;]*m|§.", "", line).rstrip()


def is_server_error(line: str) -> bool:
    line = normalize_console_line(line)
    severity = r"\[(?:[^\]\r\n]*(?:/|\s))?(?:ERROR|FATAL)\]|^\s*(?:ERROR|FATAL)\b"
    startup_severity = r"^\d{4}-\d\d-\d\d[ T]\d\d:\d\d:\d\d(?:[.,]\d+)?\s+\S+\s+(?:ERROR|FATAL)\b"
    storage_failure = r"\bFailed to (?:store|save|write|read|load) chunk\b"
    return bool(re.search(severity, line) or re.search(startup_severity, line)
                or re.search(storage_failure, line, re.IGNORECASE)
                or line.startswith("Exception in thread "))


def reject_server_error(line: str) -> None:
    if is_server_error(line):
        raise BenchmarkError("Server logged an error: " + normalize_console_line(line))


def scan_server_errors(path: Path) -> list[dict]:
    with path.open(encoding="utf-8", errors="replace") as log:
        return [{"line": number, "text": normalize_console_line(line)}
                for number, line in enumerate(log, start=1) if is_server_error(line)]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def mod_ids(path: Path) -> set[str]:
    with zipfile.ZipFile(path) as jar:
        if "META-INF/mods.toml" not in jar.namelist():
            return set()
        metadata = tomllib.loads(jar.read("META-INF/mods.toml").decode("utf-8"))
        return {mod["modId"] for mod in metadata.get("mods", [])}


def coordinates(args):
    minimum_x, maximum_x = (args.center_x - args.radius) // 16, (args.center_x + args.radius) // 16
    minimum_z, maximum_z = (args.center_z - args.radius) // 16, (args.center_z + args.radius) // 16
    return [(x, z) for z in range(minimum_z, maximum_z + 1)
            for x in range(minimum_x, maximum_x + 1)]


def region_location(world: Path, x: int, z: int) -> tuple[Path, int]:
    return world / "region" / f"r.{x // 32}.{z // 32}.mca", 4 * ((x % 32) + (z % 32) * 32)


def ensure_ungenerated(world: Path, chunks) -> None:
    for x, z in chunks:
        path, offset = region_location(world, x, z)
        if path.exists():
            with path.open("rb") as region:
                region.seek(offset)
                location = region.read(4)
                if len(location) != 4:
                    raise BenchmarkError(f"Truncated region header: {path}")
                if location != b"\0\0\0\0":
                    raise BenchmarkError(f"Selected chunk {x},{z} already exists in {world}")


def nbt_status(payload: bytes) -> str | None:
    """Read only the root Status field from modern chunk NBT; skip all other tags."""
    source = io.BytesIO(payload)

    def read(size):
        if size < 0 or size > len(payload):
            raise BenchmarkError("Invalid NBT length")
        value = source.read(size)
        if len(value) != size:
            raise BenchmarkError("Truncated NBT")
        return value

    def string():
        return read(struct.unpack(">H", read(2))[0]).decode("utf-8")

    def skip(kind, depth=0):
        if depth > 128:
            raise BenchmarkError("Excessively nested NBT")
        widths = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
        if kind in widths:
            read(widths[kind])
        elif kind in (7, 11, 12):
            size = struct.unpack(">i", read(4))[0]
            read(size * {7: 1, 11: 4, 12: 8}[kind])
        elif kind == 8:
            read(struct.unpack(">H", read(2))[0])
        elif kind == 9:
            element = read(1)[0]
            count = struct.unpack(">i", read(4))[0]
            if count < 0 or count > len(payload):
                raise BenchmarkError("Invalid NBT list length")
            for _ in range(count):
                skip(element, depth + 1)
        elif kind == 10:
            while (child := read(1)[0]) != 0:
                string()
                skip(child, depth + 1)
        else:
            raise BenchmarkError(f"Unsupported NBT tag {kind}")

    if read(1)[0] != 10:
        raise BenchmarkError("Chunk NBT root is not a compound")
    string()
    status = None
    while (kind := read(1)[0]) != 0:
        name = string()
        if name == "Status" and kind == 8:
            status = string()
        else:
            skip(kind)
    if source.tell() != len(payload):
        raise BenchmarkError("Trailing data after chunk NBT root")
    return status


def verify_full_chunks(world: Path, chunks) -> int:
    for x, z in chunks:
        path, offset = region_location(world, x, z)
        if not path.is_file():
            raise BenchmarkError(f"Missing region for target chunk {x},{z}")
        with path.open("rb") as region:
            region.seek(offset)
            location = region.read(4)
            if len(location) != 4 or location == b"\0\0\0\0":
                raise BenchmarkError(f"Target chunk {x},{z} was not saved")
            sector, sectors = int.from_bytes(location[:3], "big"), location[3]
            if sector < 2 or sectors == 0:
                raise BenchmarkError(f"Invalid region entry for {x},{z}")
            region.seek(sector * 4096)
            header = region.read(5)
            if len(header) != 5:
                raise BenchmarkError(f"Truncated chunk {x},{z}")
            length, compression = struct.unpack(">IB", header)
            if length < 1 or length > sectors * 4096 - 4:
                raise BenchmarkError(f"Invalid chunk record length for {x},{z}")
            if compression & 128:
                payload = (path.parent / f"c.{x}.{z}.mcc").read_bytes()
            else:
                payload = region.read(length - 1)
                if len(payload) != length - 1:
                    raise BenchmarkError(f"Truncated payload for {x},{z}")
            method = compression & 127
            if method == 1:
                payload = gzip.decompress(payload)
            elif method == 2:
                payload = zlib.decompress(payload)
            elif method != 3:
                raise BenchmarkError(f"Unsupported chunk compression {method}")
            status = nbt_status(payload)
            if status not in ("full", "minecraft:full"):
                raise BenchmarkError(f"Target chunk {x},{z} has status {status!r}, not FULL")
    return len(chunks)


def reject_links(root: Path) -> None:
    for directory, names, files in os.walk(root):
        for name in names + files:
            path = Path(directory) / name
            attributes = getattr(path.lstat(), "st_file_attributes", 0)
            if path.is_symlink() or attributes & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0):
                raise BenchmarkError(f"Templates must not contain links/junctions: {path}")


def prepare_server(args, run: Path, candidate: str) -> Path:
    server = run / "server"

    def ignore(directory, names):
        ignored = {name for name in names if name in {"logs", "crash-reports", "session.lock"}}
        for name in names:
            path = Path(directory) / name
            if path.is_dir() and (path / "level.dat").is_file():
                ignored.add(name)
            if path.is_file() and path.suffix.lower() == ".jar" and "mods" in path.parts:
                if mod_ids(path) & {"t2me", "chunky"}:
                    ignored.add(name)
        return ignored

    shutil.copytree(args.server_template, server, ignore=ignore)
    world = server / "benchmark-world"
    if world.exists():
        raise BenchmarkError("Template contains reserved directory benchmark-world without level.dat")
    shutil.copytree(args.world_template, world, ignore=shutil.ignore_patterns("session.lock"))
    mods = server / "mods"
    mods.mkdir(exist_ok=True)
    jar = args.t2me_jar if candidate == "t2me" else args.chunky_jar
    shutil.copy2(jar, mods / jar.name)

    # Override only the isolated copy. No process ever uses the template as cwd.
    properties = server / "server.properties"
    overrides = {"level-name": "benchmark-world", "server-ip": "127.0.0.1", "server-port": "0",
                 "enable-query": "false", "enable-rcon": "false", "enable-status": "false",
                 "white-list": "true", "enforce-whitelist": "true"}
    lines = properties.read_text(encoding="utf-8").splitlines() if properties.exists() else []
    lines = [line for line in lines if line.split("=", 1)[0].strip() not in overrides]
    lines += [f"{key}={value}" for key, value in overrides.items()]
    properties.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return server


class Server:
    def __init__(self, command, directory: Path, run: Path):
        self.lines = queue.Queue()
        self.commands = (run / "commands.jsonl").open("w", encoding="utf-8")
        self.log = (run / "console.log").open("w", encoding="utf-8")
        self.process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        text=True, encoding="utf-8", errors="replace", bufsize=1,
                                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        try:
            for line in self.process.stdout:
                timestamp = time.perf_counter()
                self.log.write(line)
                self.log.flush()
                plain = normalize_console_line(line)
                self.lines.put((timestamp, plain))
        finally:
            self.lines.put((time.perf_counter(), None))

    def send(self, command: str):
        if self.process.poll() is not None:
            raise BenchmarkError(f"Server exited with {self.process.returncode}")
        self.commands.write(json.dumps({"time": time.time(), "command": command}) + "\n")
        self.commands.flush()
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def next_line(self, deadline):
        remaining = deadline - time.perf_counter()
        if remaining <= 0:
            raise BenchmarkError("Timed out waiting for the server; inspect console.log")
        try:
            timestamp, line = self.lines.get(timeout=remaining)
        except queue.Empty as error:
            raise BenchmarkError("Timed out waiting for the server; inspect console.log") from error
        if line is None:
            raise BenchmarkError(f"Server output closed (exit={self.process.poll()})")
        reject_server_error(line)
        return timestamp, line

    def wait(self, pattern, timeout):
        deadline = time.perf_counter() + timeout
        while True:
            timestamp, line = self.next_line(deadline)
            if re.search(pattern, line):
                return timestamp
            if "Unknown or incomplete command" in line or "Incorrect argument for command" in line:
                raise BenchmarkError(f"Console command rejected: {line}")

    def barrier(self, timeout):
        marker = "T2ME_BENCHMARK_" + uuid.uuid4().hex
        self.send("say " + marker)
        self.wait(re.escape(marker), timeout)

    def stop(self, timeout):
        clean = True
        try:
            if self.process.poll() is None:
                try:
                    self.send("stop")
                except (BrokenPipeError, OSError, BenchmarkError):
                    clean = False
                try:
                    self.process.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    clean = False
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        self.process.kill()
                        self.process.wait(timeout=10)
        finally:
            self.reader.join(timeout=5)
            self.commands.close()
            if not self.reader.is_alive():
                self.log.close()
        return clean and self.process.returncode == 0 and not self.reader.is_alive()


def run_candidate(args, candidate: str, number: int, chunks) -> dict:
    run = args.output / f"{number:02d}-{candidate}"
    run.mkdir()
    result = {"candidate": candidate, "directory": str(run), "success": False}
    server = None
    try:
        directory = prepare_server(args, run, candidate)
        world = directory / "benchmark-world"
        ensure_ungenerated(world, chunks)
        server = Server(args.command, directory, run)
        server.wait(r'Done \([\d.,]+s\)! For help', args.startup_timeout)
        time.sleep(args.settle_seconds)
        server.send("save-all flush")
        server.wait(r"Saved the game", args.startup_timeout)
        ensure_ungenerated(world, chunks)
        if candidate == "t2me":
            server.send("t2me config show")
            start_command = (f"t2me pregen startat minecraft:overworld {args.center_x} "
                             f"{args.center_z} {args.radius} square")
        else:
            server.send(f"chunky world {args.chunky_world}")
            server.send(f"chunky corners {args.center_x - args.radius} {args.center_z - args.radius} "
                        f"{args.center_x + args.radius} {args.center_z + args.radius}")
            server.send("chunky selection")
            start_command = "chunky start"
        server.barrier(args.startup_timeout)
        started = time.perf_counter()
        server.send(start_command)
        deadline = started + args.generation_timeout
        while True:
            timestamp, line = server.next_line(deadline)
            if candidate == "t2me":
                if re.search(r"state=(paused|failed|cancelled)\b", line) or "start blocked" in line:
                    raise BenchmarkError(f"T2ME stopped without completing: {line}")
                progress = re.search(r"T2ME job=.*done=(\d+)/(\d+)", line)
                if progress and int(progress[2]) != len(chunks):
                    raise BenchmarkError(f"T2ME target mismatch: {progress[2]} != {len(chunks)}")
                if progress and "state=completed" in line:
                    count = int(progress[1])
                    break
            else:
                finished = re.search(r"Task finished for " + re.escape(args.chunky_world)
                                     + r"\. Processed: ([\d,]+) chunks \(100[.,]0+%\)", line)
                if finished:
                    count = int(finished[1].replace(",", ""))
                    break
                if "Task stopped for" in line or "Task paused for" in line:
                    raise BenchmarkError(f"Chunky stopped without completing: {line}")
            if "Unknown or incomplete command" in line:
                raise BenchmarkError(f"Pregenerator command rejected: {line}")
        if count != len(chunks):
            raise BenchmarkError(f"Completed count mismatch: {count} != {len(chunks)}")
        result["reported_chunks"] = count
        result["completion_seconds"] = timestamp - started
        server.send("save-all flush")
        flushed = server.wait(r"Saved the game", args.generation_timeout)
        result["completion_and_flush_seconds"] = flushed - started
        # Check before stop: its second save must not hide an incomplete timed flush.
        result["verified_full_chunks"] = verify_full_chunks(world, chunks)
        clean = server.stop(args.shutdown_timeout)
        server = None
        result["clean_shutdown"] = clean
        if not clean:
            raise BenchmarkError("Server failed to shut down cleanly")
        result["completion_cps"] = count / result["completion_seconds"]
        result["completion_and_flush_cps"] = count / result["completion_and_flush_seconds"]
        result["success"] = True
    except (Exception, KeyboardInterrupt) as error:
        result["error"] = f"{type(error).__name__}: {error}"
        if isinstance(error, KeyboardInterrupt):
            result["interrupted"] = True
    finally:
        if server is not None:
            try:
                result["clean_shutdown"] = server.stop(args.shutdown_timeout)
            except Exception as error:
                result["clean_shutdown"] = False
                result["shutdown_error"] = f"{type(error).__name__}: {error}"
        # A later retry or the final shutdown save must not turn an earlier
        # storage/generation error into an apparently successful benchmark.
        # Audit the complete log after stop, including errors after completion.
        console_log = run / "console.log"
        if console_log.exists():
            result["server_errors"] = scan_server_errors(console_log)
            if result["server_errors"]:
                result["success"] = False
                result["error"] = (f"Server logged {len(result['server_errors'])} error(s); "
                                   f"console.log:{result['server_errors'][0]['line']}: "
                                   + result["server_errors"][0]["text"])
                result.pop("completion_cps", None)
                result.pop("completion_and_flush_cps", None)
        elif result["success"]:
            result["success"] = False
            result["error"] = "Missing console.log; cannot audit server errors"
        (run / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-template", required=True, type=Path)
    parser.add_argument("--world-template", required=True, type=Path)
    parser.add_argument("--t2me-jar", required=True, type=Path)
    parser.add_argument("--chunky-jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="new directory; runs are retained")
    parser.add_argument("--repeats", type=int, default=3, help="paired runs; default 3")
    parser.add_argument("--center-x", type=int, default=8192)
    parser.add_argument("--center-z", type=int, default=8192)
    parser.add_argument("--radius", type=int, default=128, help="multiple of 16 blocks, 32..2048")
    parser.add_argument("--chunky-world", default="minecraft:overworld")
    parser.add_argument("--startup-timeout", type=float, default=300)
    parser.add_argument("--generation-timeout", type=float, default=1800)
    parser.add_argument("--shutdown-timeout", type=float, default=120)
    parser.add_argument("--settle-seconds", type=float, default=10)
    parser.add_argument("--command", nargs=argparse.REMAINDER, required=True,
                        help="direct Java executable and all arguments; must be the last option")
    return parser.parse_args()


def main():
    args = parse_args()
    for key in ("server_template", "world_template", "t2me_jar", "chunky_jar", "output"):
        setattr(args, key, getattr(args, key).resolve())
    if not args.command or Path(args.command[0]).suffix.lower() in {".bat", ".cmd", ".ps1", ".sh"}:
        raise BenchmarkError("Use a direct Java executable, not a shell script or launcher wrapper")
    if not 1 <= args.repeats <= 20 or not 32 <= args.radius <= 2048 or args.radius % 16:
        raise BenchmarkError("repeats must be 1..20 and radius must be a multiple of 16 within 32..2048")
    if args.center_x % 16 or args.center_z % 16:
        raise BenchmarkError("Both centers must be multiples of 16 for identical Chunky/T2ME coverage")
    if max(abs(args.center_x), abs(args.center_z)) + args.radius > 29_999_984:
        raise BenchmarkError("Selection exceeds Minecraft's supported coordinates")
    if min(args.startup_timeout, args.generation_timeout, args.shutdown_timeout) <= 0 or args.settle_seconds < 0:
        raise BenchmarkError("Timeouts must be positive and settle-seconds non-negative")
    if args.output.exists() or any(args.output.is_relative_to(path)
                                   for path in (args.server_template, args.world_template)):
        raise BenchmarkError("Output must be a new directory outside both templates")
    if args.server_template.is_relative_to(args.world_template):
        raise BenchmarkError("The world template must not contain the server template")
    for root in (args.server_template, args.world_template):
        if not root.is_dir():
            raise BenchmarkError(f"Missing template directory: {root}")
        reject_links(root)
    if not (args.world_template / "level.dat").is_file():
        raise BenchmarkError("World template must contain an existing level.dat")
    if (args.world_template / "data" / "t2me_jobs.dat").exists():
        raise BenchmarkError("World template must not contain an existing T2ME checkpoint")
    for tasks in (args.server_template / "config" / "chunky" / "tasks",
                  args.server_template / "chunky" / "tasks"):
        if tasks.exists() and any(tasks.iterdir()):
            raise BenchmarkError(f"Template contains saved Chunky tasks: {tasks}")
    eula = args.server_template / "eula.txt"
    if not eula.is_file() or not re.search(r"(?m)^\s*eula\s*=\s*true\s*$", eula.read_text(encoding="utf-8")):
        raise BenchmarkError("Prepared server template must already have eula=true")
    for name in ("t2me", "chunky"):
        path = getattr(args, name + "_jar")
        if mod_ids(path) & {"t2me", "chunky"} != {name}:
            raise BenchmarkError(f"Expected only the {name} candidate mod in {path}")
    chunks = coordinates(args)
    ensure_ungenerated(args.world_template, chunks)
    args.output.mkdir(parents=True)
    report = {"schema": 1, "created_epoch_seconds": time.time(), "command": args.command,
              "python": sys.version, "platform": sys.platform, "cpu_count": os.cpu_count(),
              "server_template": str(args.server_template), "world_template": str(args.world_template),
              "world_level_dat_sha256": sha256(args.world_template / "level.dat"),
              "selection": {"dimension": "minecraft:overworld", "shape": "square",
                            "center_x": args.center_x, "center_z": args.center_z, "radius": args.radius,
                            "expected_chunks": len(chunks)},
              "jars": {name: {"file": str(getattr(args, name + "_jar")),
                              "sha256": sha256(getattr(args, name + "_jar"))}
                       for name in ("t2me", "chunky")},
              "runs": [], "comparable": False}

    def save_report():
        (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    save_report()
    for repeat in range(args.repeats):
        order = ("t2me", "chunky") if repeat % 2 == 0 else ("chunky", "t2me")
        for candidate in order:
            print(f"Pair {repeat + 1}/{args.repeats}: {candidate}, {len(chunks)} chunks", flush=True)
            result = run_candidate(args, candidate, len(report["runs"]) + 1, chunks)
            result["pair"] = repeat + 1
            report["runs"].append(result)
            save_report()
            if not result["success"]:
                print(f"Comparison refused: {result['error']}. See {args.output / 'report.json'}", file=sys.stderr)
                return 1
    report["comparable"] = True
    report["medians"] = {
        name: {metric: statistics.median(run[metric] for run in report["runs"] if run["candidate"] == name)
               for metric in ("completion_seconds", "completion_and_flush_seconds")}
        for name in ("t2me", "chunky")}
    report["chunky_time_divided_by_t2me_time"] = {
        metric: report["medians"]["chunky"][metric] / report["medians"]["t2me"][metric]
        for metric in ("completion_seconds", "completion_and_flush_seconds")}
    save_report()
    print(json.dumps({"medians": report["medians"],
                      "report": str(args.output / "report.json")}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (BenchmarkError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Benchmark refused: {error}", file=sys.stderr)
        raise SystemExit(1)
