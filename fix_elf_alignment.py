#!/usr/bin/env python3
"""
Patch ELF shared libraries to use 16KB page alignment.
Inserts padding so LOAD segment file offsets are congruent
to their virtual addresses modulo 16384, then sets p_align = 16384.
"""

import struct, sys, os, glob, shutil

PAGE = 16384  # 16 KB


def align_elf(path):
    with open(path, "rb") as f:
        data = bytearray(f.read())

    if data[:4] != b"\x7fELF":
        return False

    ei_class = data[4]  # 1 = 32-bit, 2 = 64-bit
    is64 = ei_class == 2

    if is64:
        e_phoff    = struct.unpack_from("<Q", data, 32)[0]
        e_phentsize = struct.unpack_from("<H", data, 54)[0]
        e_phnum    = struct.unpack_from("<H", data, 56)[0]
        e_shoff    = struct.unpack_from("<Q", data, 40)[0]
        e_shentsize = struct.unpack_from("<H", data, 58)[0]
        e_shnum    = struct.unpack_from("<H", data, 60)[0]
    else:
        e_phoff    = struct.unpack_from("<I", data, 28)[0]
        e_phentsize = struct.unpack_from("<H", data, 42)[0]
        e_phnum    = struct.unpack_from("<H", data, 44)[0]
        e_shoff    = struct.unpack_from("<I", data, 32)[0]
        e_shentsize = struct.unpack_from("<H", data, 46)[0]
        e_shnum    = struct.unpack_from("<H", data, 48)[0]

    # ── Collect LOAD segments ──────────────────────────────────
    loads = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type != 1:  # PT_LOAD
            continue
        if is64:
            p_offset  = struct.unpack_from("<Q", data, off + 8)[0]
            p_vaddr   = struct.unpack_from("<Q", data, off + 16)[0]
            p_filesz  = struct.unpack_from("<Q", data, off + 32)[0]
            p_align   = struct.unpack_from("<Q", data, off + 48)[0]
        else:
            p_offset  = struct.unpack_from("<I", data, off + 4)[0]
            p_vaddr   = struct.unpack_from("<I", data, off + 8)[0]
            p_filesz  = struct.unpack_from("<I", data, off + 16)[0]
            p_align   = struct.unpack_from("<I", data, off + 28)[0]
        loads.append({
            "idx": i, "hdr_off": off,
            "p_offset": p_offset, "p_vaddr": p_vaddr,
            "p_filesz": p_filesz, "p_align": p_align,
        })

    if not loads:
        return False

    # Check if already 16KB-aligned
    already_ok = True
    for L in loads:
        if L["p_align"] < PAGE:
            already_ok = False
            break
        if L["p_offset"] % PAGE != L["p_vaddr"] % PAGE:
            already_ok = False
            break
    if already_ok:
        return True

    # ── Sort LOAD segments by file offset ────────────────────
    loads.sort(key=lambda x: x["p_offset"])

    # ── Build a list of (insert_position, pad_bytes) ─────────
    patches = []  # (file_position, bytes_to_insert)
    cumulative_shift = 0

    for L in loads:
        cur_off = L["p_offset"] + cumulative_shift
        vaddr   = L["p_vaddr"]
        need    = vaddr % PAGE
        have    = cur_off % PAGE
        if need != have:
            if need >= have:
                pad = need - have
            else:
                pad = PAGE - have + need
            patches.append((L["p_offset"] + cumulative_shift, pad, L))
            cumulative_shift += pad

    if cumulative_shift == 0:
        # Just need to update p_align values
        for L in loads:
            hdr = L["hdr_off"]
            if is64:
                struct.pack_into("<Q", data, hdr + 48, PAGE)
            else:
                struct.pack_into("<I", data, hdr + 28, PAGE)
        with open(path, "wb") as f:
            f.write(data)
        return True

    # ── Insert padding into the bytearray ────────────────────
    # Process patches in forward order: each insert_pos already accounts
    # for cumulative_shift from previous patches, so positions are correct
    # in the progressively-modified bytearray.
    for insert_pos, pad, _ in patches:
        data[insert_pos:insert_pos] = b"\x00" * pad

    # ── Update program headers ───────────────────────────────
    # Recalculate all program header offsets (not just LOAD)
    shift_table = []  # sorted list of (original_pos, shift_amount)
    cum = 0
    for _, pad, L in patches:
        shift_table.append((L["p_offset"], cum + pad))
        cum += pad

    def shifted_offset(orig):
        s = 0
        for pos, shift in shift_table:
            if orig >= pos:
                s = shift
            else:
                break
        return orig + s

    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if is64:
            p_offset = struct.unpack_from("<Q", data, off + 8)[0]
            new_off = shifted_offset(p_offset)
            struct.pack_into("<Q", data, off + 8, new_off)
            if p_type == 1:  # PT_LOAD: set p_align = 16384
                struct.pack_into("<Q", data, off + 48, PAGE)
        else:
            p_offset = struct.unpack_from("<I", data, off + 4)[0]
            new_off = shifted_offset(p_offset)
            struct.pack_into("<I", data, off + 4, new_off)
            if p_type == 1:
                struct.pack_into("<I", data, off + 28, PAGE)

    # ── Update section headers ───────────────────────────────
    new_shoff = shifted_offset(e_shoff)
    if is64:
        struct.pack_into("<Q", data, 40, new_shoff)
    else:
        struct.pack_into("<I", data, 32, new_shoff)

    for i in range(e_shnum):
        sh_off_pos = new_shoff + i * e_shentsize
        if is64:
            sh_offset = struct.unpack_from("<Q", data, sh_off_pos + 24)[0]
            struct.pack_into("<Q", data, sh_off_pos + 24, shifted_offset(sh_offset))
        else:
            sh_offset = struct.unpack_from("<I", data, sh_off_pos + 16)[0]
            struct.pack_into("<I", data, sh_off_pos + 16, shifted_offset(sh_offset))

    with open(path, "wb") as f:
        f.write(data)
    return True


def main():
    base = os.path.dirname(os.path.abspath(__file__))
    aar_path = None

    # Find the pdfium AAR in Gradle cache
    home = os.path.expanduser("~")
    for root, dirs, files in os.walk(os.path.join(home, ".gradle", "caches", "modules-2")):
        for f in files:
            if f == "pdfium-android-1.9.2.aar":
                aar_path = os.path.join(root, f)
                break
        if aar_path:
            break

    if not aar_path:
        print("ERROR: Could not find pdfium-android-1.9.2.aar in Gradle cache")
        sys.exit(1)

    # Also find ML Kit text-recognition-bundled-common AAR (contains libmlkit_google_ocr_pipeline.so)
    mlkit_aar_path = None
    for root, dirs, files in os.walk(os.path.join(home, ".gradle", "caches", "modules-2")):
        for f in files:
            if f.startswith("text-recognition-bundled-common-") and f.endswith(".aar"):
                candidate = os.path.join(root, f)
                # Pick the newest version
                if mlkit_aar_path is None or f > os.path.basename(mlkit_aar_path):
                    mlkit_aar_path = candidate

    jnilibs = os.path.join(base, "app", "src", "main", "jniLibs")

    # Extract .so from AAR(s)
    import zipfile
    aar_list = [aar_path]
    if mlkit_aar_path:
        aar_list.append(mlkit_aar_path)
        print(f"  Found ML Kit AAR: {os.path.basename(mlkit_aar_path)}")
    else:
        print("  WARNING: Could not find text-recognition-bundled-common AAR")

    for aar in aar_list:
        with zipfile.ZipFile(aar, "r") as zf:
            for info in zf.infolist():
                if info.filename.startswith("jni/") and info.filename.endswith(".so"):
                    # jni/arm64-v8a/libfoo.so → arm64-v8a/libfoo.so
                    parts = info.filename.split("/")
                    if len(parts) == 3:
                        arch, name = parts[1], parts[2]
                        dest_dir = os.path.join(jnilibs, arch)
                        os.makedirs(dest_dir, exist_ok=True)
                        dest = os.path.join(dest_dir, name)
                        with zf.open(info) as src, open(dest, "wb") as dst:
                            dst.write(src.read())

    # Align all .so files
    ok = 0
    fail = 0
    for so_path in sorted(glob.glob(os.path.join(jnilibs, "**", "*.so"), recursive=True)):
        rel = os.path.relpath(so_path, jnilibs)
        try:
            result = align_elf(so_path)
            if result:
                # Verify
                with open(so_path, "rb") as f:
                    d = f.read()
                ei = d[4]
                is64 = ei == 2
                if is64:
                    ph_off = struct.unpack_from("<Q", d, 32)[0]
                    ph_ent = struct.unpack_from("<H", d, 54)[0]
                    ph_num = struct.unpack_from("<H", d, 56)[0]
                else:
                    ph_off = struct.unpack_from("<I", d, 28)[0]
                    ph_ent = struct.unpack_from("<H", d, 42)[0]
                    ph_num = struct.unpack_from("<H", d, 44)[0]
                all_good = True
                for i in range(ph_num):
                    o = ph_off + i * ph_ent
                    pt = struct.unpack_from("<I", d, o)[0]
                    if pt == 1:
                        if is64:
                            pa = struct.unpack_from("<Q", d, o + 48)[0]
                            po = struct.unpack_from("<Q", d, o + 8)[0]
                            pv = struct.unpack_from("<Q", d, o + 16)[0]
                        else:
                            pa = struct.unpack_from("<I", d, o + 28)[0]
                            po = struct.unpack_from("<I", d, o + 4)[0]
                            pv = struct.unpack_from("<I", d, o + 8)[0]
                        if pa < PAGE or (po % PAGE != pv % PAGE):
                            all_good = False
                if all_good:
                    print(f"  ✅ {rel}")
                    ok += 1
                else:
                    print(f"  ⚠️  {rel} (partial)")
                    fail += 1
            else:
                print(f"  ❌ {rel} (not ELF or no LOAD)")
                fail += 1
        except Exception as e:
            print(f"  ❌ {rel}: {e}")
            fail += 1

    print(f"\n{ok} aligned, {fail} failed")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

