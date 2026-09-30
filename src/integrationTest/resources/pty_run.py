"""Runs a command inside a pseudo-terminal of a fixed size and prints everything it wrote.

Usage: pty_run.py [--cols N] [--rows N] [--interrupt-after TEXT] [--step wait:TEXT|send:KEYS]...
                  [--timeout SECONDS] -- COMMAND...

The command's terminal output is written to stdout as raw bytes, and this script exits
with the command's exit code.

--interrupt-after TEXT types Ctrl-C as soon as TEXT appears in the output.

--step runs in order:
  wait:TEXT  waits until TEXT appears in the output written since the previous step,
             with escape codes removed.
  send:KEYS  types KEYS. Python escapes are allowed, e.g. send:\\x1b[B for arrow down.

Tests therefore never have to guess timings.
"""
import argparse
import codecs
import fcntl
import os
import pty
import re
import select
import signal
import struct
import sys
import termios
import time

ESCAPE_CODES = re.compile(rb"\x1b\[[0-?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[=>78]")


def visible(data):
    return ESCAPE_CODES.sub(b"", data).replace(b"\r", b"")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cols", type=int, default=100)
    parser.add_argument("--rows", type=int, default=40)
    parser.add_argument("--interrupt-after")
    parser.add_argument("--step", action="append", default=[])
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command

    steps = []
    if args.interrupt_after:
        steps += [("wait", args.interrupt_after.encode()), ("send", b"\x03")]
    for step in args.step:
        kind, _, value = step.partition(":")
        if kind == "wait":
            steps.append(("wait", value.encode()))
        elif kind == "send":
            steps.append(("send", codecs.decode(value, "unicode_escape").encode("latin-1")))
        else:
            parser.error("unknown step: " + step)

    pid, fd = pty.fork()
    if pid == 0:
        fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", args.rows, args.cols, 0, 0))
        os.execvp(command[0], command)

    output = bytearray()
    step_start = 0

    def run_ready_steps():
        nonlocal step_start
        while steps:
            kind, value = steps[0]
            if kind == "wait":
                if value not in visible(bytes(output[step_start:])):
                    return
                step_start = len(output)
            else:
                os.write(fd, value)
                step_start = len(output)
            steps.pop(0)

    deadline = time.monotonic() + args.timeout
    while True:
        run_ready_steps()
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)
            sys.stdout.buffer.write(output)
            sys.stderr.write("pty_run: timed out after %s seconds; pending steps: %r\n" % (args.timeout, steps))
            sys.exit(124)
        ready, _, _ = select.select([fd], [], [], remaining)
        if not ready:
            continue
        try:
            chunk = os.read(fd, 65536)
        except OSError:
            # Linux reports EIO once the child has exited and closed the terminal.
            break
        if not chunk:
            break
        output += chunk

    _, status = os.waitpid(pid, 0)
    sys.stdout.buffer.write(output)
    sys.stdout.flush()
    if steps:
        sys.stderr.write("pty_run: the command exited before these steps ran: %r\n" % steps)
    sys.exit(os.waitstatus_to_exitcode(status))


if __name__ == "__main__":
    main()
