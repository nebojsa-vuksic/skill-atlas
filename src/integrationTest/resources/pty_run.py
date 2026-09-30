"""Runs a command inside a pseudo-terminal of a fixed size and prints everything it wrote.

Usage: pty_run.py [--cols N] [--rows N] [--interrupt-after TEXT] [--timeout SECONDS] -- COMMAND...

The command's terminal output is written to stdout as raw bytes, and this script exits
with the command's exit code. With --interrupt-after, Ctrl-C is typed into the terminal
as soon as TEXT appears in the output, so tests never have to guess timings.
"""
import argparse
import fcntl
import os
import pty
import select
import signal
import struct
import sys
import termios
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cols", type=int, default=100)
    parser.add_argument("--rows", type=int, default=40)
    parser.add_argument("--interrupt-after")
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command

    pid, fd = pty.fork()
    if pid == 0:
        fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", args.rows, args.cols, 0, 0))
        os.execvp(command[0], command)

    marker = args.interrupt_after.encode() if args.interrupt_after else None
    interrupted = False
    output = bytearray()
    deadline = time.monotonic() + args.timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)
            sys.stdout.buffer.write(output)
            sys.stderr.write("pty_run: timed out after %s seconds\n" % args.timeout)
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
        if marker and not interrupted and marker in output:
            os.write(fd, b"\x03")
            interrupted = True

    _, status = os.waitpid(pid, 0)
    sys.stdout.buffer.write(output)
    sys.stdout.flush()
    sys.exit(os.waitstatus_to_exitcode(status))


if __name__ == "__main__":
    main()
