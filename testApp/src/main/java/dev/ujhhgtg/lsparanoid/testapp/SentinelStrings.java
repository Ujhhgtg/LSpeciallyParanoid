package dev.ujhhgtg.lsparanoid.testapp;

import dev.ujhhgtg.lsparanoid.Obfuscate;

/** Unique sentinels let release verification inspect both behavior and final DEX contents. */
@Obfuscate
public final class SentinelStrings {
    private SentinelStrings() {}

    public static String ascii() { return "lsp-native-literal-sentinel-b69ce2d6"; }
    public static String empty() { return ""; }
    public static String utf16() { return "中文\0\ud800unpaired\udfff\ud834\udd1e"; }
    public static String duplicateOne() { return "lsp-duplicate-site-sentinel-733019"; }
    public static String duplicateTwo() { return "lsp-duplicate-site-sentinel-733019"; }

    public static String longLiteral() {
        return "lsp-long-sentinel-c70b32e8:"
                + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    }
}
