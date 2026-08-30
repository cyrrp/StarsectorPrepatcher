package com.starsector.prepatcher.agent;

import java.util.Set;

/**
 * Repairs illegal obfuscator member names before Java 27 parses a game class.
 *
 * <p>Vanilla game bytes and the bytes returned by the legacy Faster Rendering loader contain
 * names such as {@code while.new}. Java 17 accepted them when verification was disabled; Java 27
 * rejects them in the class-file parser. The replacement is deliberately performed directly in
 * CONSTANT_Utf8 entries: a dot becomes an underscore, so indices, lengths, descriptors, frames,
 * method bodies, and every declaration/reference user remain unchanged.</p>
 */
final class IllegalObfuscatedMemberNameRepair {
    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final Set<String> ILLEGAL_SEGMENTS = Set.of(
            "class", "do", "float", "for", "if", "int", "interface", "new",
            "null", "Object", "private", "public", "return", "String", "super",
            "this", "void", "while");

    private IllegalObfuscatedMemberNameRepair() {
    }

    static byte[] repair(String resourceName, byte[] classfile) {
        if (!isGameClass(resourceName) || classfile == null) return classfile;
        requireRange(classfile, 0, 10);
        if (readInt(classfile, 0) != CLASS_MAGIC) {
            throw new IllegalArgumentException("illegal-name repair received non-class bytes for "
                    + resourceName);
        }

        byte[] result = null;
        int constantPoolCount = readUnsignedShort(classfile, 8);
        int cursor = 10;
        for (int index = 1; index < constantPoolCount; index++) {
            requireRange(classfile, cursor, 1);
            int tag = classfile[cursor++] & 0xff;
            switch (tag) {
                case 1 -> {
                    requireRange(classfile, cursor, 2);
                    int length = readUnsignedShort(classfile, cursor);
                    int valueOffset = cursor + 2;
                    requireRange(classfile, valueOffset, length);
                    int dotOffset = illegalNameDot(classfile, valueOffset, length);
                    if (dotOffset >= 0) {
                        if (result == null) result = classfile.clone();
                        result[dotOffset] = (byte) '_';
                    }
                    cursor = valueOffset + length;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> cursor = advance(classfile, cursor, 4);
                case 5, 6 -> {
                    cursor = advance(classfile, cursor, 8);
                    index++;
                }
                case 7, 8, 16, 19, 20 -> cursor = advance(classfile, cursor, 2);
                case 15 -> cursor = advance(classfile, cursor, 3);
                default -> throw new IllegalArgumentException(
                        "unsupported constant-pool tag " + tag + " in " + resourceName);
            }
        }
        return result == null ? classfile : result;
    }

    private static boolean isGameClass(String resourceName) {
        if (resourceName == null) return false;
        String normalized = resourceName.replace('.', '/');
        return normalized.startsWith("com/fs/")
                || normalized.startsWith("sound/")
                || normalized.startsWith("zzz/com/fs/");
    }

    private static int illegalNameDot(byte[] bytes, int offset, int length) {
        int dot = -1;
        int dollar = -1;
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            int value = bytes[index] & 0xff;
            if (value > 0x7f || value == '/' || value == ';' || value == '(' || value == ')') {
                return -1;
            }
            if (value == '.') {
                if (dot >= 0) return -1;
                dot = index;
            } else if (value == '$') {
                if (dollar >= 0) return -1;
                dollar = index;
            }
        }
        if (dot <= offset || dot >= end - 1 || (dollar >= 0 && dollar <= dot + 1)) return -1;
        if (!isIllegalSegment(bytes, offset, dot)) return -1;
        int secondEnd = dollar < 0 ? end : dollar;
        if (!isIllegalSegment(bytes, dot + 1, secondEnd)) return -1;
        if (dollar >= 0 && !isIllegalSegment(bytes, dollar + 1, end)) return -1;
        return dot;
    }

    private static boolean isIllegalSegment(byte[] bytes, int start, int end) {
        if (start >= end) return false;
        StringBuilder value = new StringBuilder(end - start);
        for (int index = start; index < end; index++) value.append((char) (bytes[index] & 0xff));
        return ILLEGAL_SEGMENTS.contains(value.toString());
    }

    private static int advance(byte[] bytes, int cursor, int count) {
        requireRange(bytes, cursor, count);
        return cursor + count;
    }

    private static int readUnsignedShort(byte[] bytes, int offset) {
        requireRange(bytes, offset, 2);
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int readInt(byte[] bytes, int offset) {
        requireRange(bytes, offset, 4);
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static void requireRange(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException("truncated class file in illegal-name repair");
        }
    }
}
