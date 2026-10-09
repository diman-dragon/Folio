#!/usr/bin/env python3
"""Проверка требования Google Play: нативные 64-битные библиотеки должны поддерживать страницы памяти 16 КБ.

Для APK/AAB проверяется:
  1. ELF: у каждого сегмента PT_LOAD выравнивание (p_align) >= 16384 (только arm64-v8a и x86_64);
  2. APK: .so хранятся без сжатия и лежат в архиве по смещению, кратному 16384.
Использование: python3 tools/check_16kb.py app-release.apk app-release.aab
Код возврата 1, если найдено нарушение.
"""
import struct
import sys
import zipfile

PAGE = 16384
PT_LOAD = 1
CHECKED_ABIS = ("arm64-v8a", "x86_64")


def elf_load_alignments(data: bytes):
    if data[:4] != b"\x7fELF" or data[4] != 2:   # только ELF64
        return None
    e = "<" if data[5] == 1 else ">"
    phoff = struct.unpack_from(e + "Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from(e + "HH", data, 0x36)
    out = []
    for i in range(phnum):
        off = phoff + i * phentsize
        if struct.unpack_from(e + "I", data, off)[0] == PT_LOAD:
            out.append(struct.unpack_from(e + "Q", data, off + 0x30)[0])
    return out


def data_offset(raw: bytes, info: zipfile.ZipInfo) -> int:
    nlen, elen = struct.unpack_from("<HH", raw, info.header_offset + 26)
    return info.header_offset + 30 + nlen + elen


def check(path: str) -> bool:
    is_apk = path.lower().endswith(".apk")
    ok = True
    raw = open(path, "rb").read()
    with zipfile.ZipFile(path) as z:
        libs = [i for i in z.infolist()
                if i.filename.endswith(".so") and any(f"/{abi}/" in i.filename for abi in CHECKED_ABIS)]
        if not libs:
            print(f"{path}: нативных 64-битных библиотек не найдено")
        for info in libs:
            al = elf_load_alignments(z.read(info))
            problems = []
            if al is None:
                continue
            if any(a < PAGE for a in al):
                problems.append("ELF-выравнивание %s < %d" % (sorted(set(al)), PAGE))
            if is_apk:
                if info.compress_type != zipfile.ZIP_STORED:
                    problems.append("библиотека сжата в архиве")
                elif data_offset(raw, info) % PAGE != 0:
                    problems.append("смещение в APK не кратно 16 КБ")
            print(("OK   " if not problems else "FAIL ") + f"{path}: {info.filename}" + ("" if not problems else " — " + "; ".join(problems)))
            ok = ok and not problems
    return ok


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    results = [check(p) for p in sys.argv[1:]]
    sys.exit(0 if all(results) else 1)
