# -*- coding: utf-8 -*-
"""
把含中文的 ProbeDiff.java 转成纯 ASCII（非 ASCII 字符 → Unicode 四位转义）的副本。

为什么需要它：JDK 17 的 "java Foo.java" 单文件源码启动**无法指定源码编码**
（实测 -encoding / -J-D 都被 Launcher 拒绝），它会按平台默认编码读源码；
本机是 cp936，于是源码里的中文字面量会乱码。JDK 18+（JEP 400）起默认按 UTF-8 读，没这个问题。

用法：
    python escape-src.py ProbeDiff.java out/ProbeDiff.ascii.java

产出的副本在 JDK 17 与 JDK 25 上行为完全一致（selftest.ps1 会逐字节比对两份报告）。
"""
import sys


def escape(text):
    out = []
    for ch in text:
        o = ord(ch)
        if o < 0x80:
            out.append(ch)
        elif o <= 0xFFFF:
            out.append("\\u%04X" % o)
        else:
            v = o - 0x10000
            out.append("\\u%04X\\u%04X" % (0xD800 + (v >> 10), 0xDC00 + (v & 0x3FF)))
    return "".join(out)


def main(argv):
    if len(argv) != 3:
        sys.stderr.write("用法: python escape-src.py <输入.java> <输出.java>\n")
        return 2
    src, dst = argv[1], argv[2]
    with open(src, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    with open(dst, "w", encoding="ascii", newline="") as f:
        f.write(escape(text))
    non_ascii = sum(1 for c in text if ord(c) >= 0x80)
    print("已转义 %d 个非 ASCII 字符: %s -> %s" % (non_ascii, src, dst))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
