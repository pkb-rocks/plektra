#!/usr/bin/env python3
"""Import supplied .env speech keys into a debug build without changing other speech settings."""
import subprocess
import sys
from debug_device import arguments, connection


def main():
    parser = arguments(__doc__)
    device, values = connection(parser.parse_args())
    device.speech(values)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError):
        print('Speech setup failed. Check env fields, device connection and debug APK.', file=sys.stderr)
        sys.exit(1)
