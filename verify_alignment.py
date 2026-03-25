#!/usr/bin/env python3
"""Verify that all .so files inside the APK have 16KB-aligned LOAD segments."""

import struct, zipfile, sys, os

PAGE = 16384

def check_elf(data):
    if data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    if is64:
        ph_off = struct.unpack_from("<Q", data, 32)[0]
        ph_ent = struct.unpack_from("<H", data, 54)[0]
        ph_num = struct.unpack_from("<H", data, 56)[0]
    else:
        ph_off = struct.unpack_from("<I", data, 28)[0]
        ph_ent = struct.unpack_from("<H", data, 42)[0]
        ph_num = struct.unpack_from("<H", data, 44)[0]
    for i in range(ph_num):
        o = ph_off + i * ph_ent
        pt = struct.unpack_from("<I", data, o)[0]
        if pt != 1:
            continue
        if is64:
            pa = struct.unpack_from("<Q", data, o + 48)[0]
            po = struct.unpack_from("<Q", data, o + 8)[0]
            pv = struct.unpack_from("<Q", data, o + 16)[0]
        else:
            pa = struct.unpack_from("<I", data, o + 28)[0]
            po = struct.unpack_from("<I", data, o + 4)[0]
            pv = struct.unpack_from("<I", data, o + 8)[0]
        if pa < PAGE or (po % PAGE != pv % PAGE):
            return False
    return True

def main():
    base = os.path.dirname(os.path.abspath(__file__))
    apk = os.path.join(base, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    if not os.path.exists(apk):
        print("APK not found: " + apk)
        return 1

    problems = []
    with zipfile.ZipFile(apk) as zf:
        for info in zf.infolist():
            if not info.filename.endswith(".so"):
                continue
            data = zf.read(info.filename)
            result = check_elf(data)
            if result is None:
                continue
            if result:
                print("  OK  " + info.filename)
            else:
                print("  BAD " + info.filename)
                problems.append(info.filename)

    if problems:
        print("\n%d libraries NOT 16KB-aligned:" % len(problems))
        for p in problems:
            print("  - " + p)
        return 1
    else:
        print("\nAll libraries are 16KB-aligned!")
        return 0

if __name__ == "__main__":
    sys.exit(main())

