package dev.ujhhgtg.lsparanoid.testapp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Calls transformed application methods; expected strings live only in the separate test APK. */
@RunWith(AndroidJUnit4.class)
public final class NativeLiteralTest {
    @Test public void literalsPreserveEveryUtf16CodeUnit() {
        assertEquals("lsp-native-literal-sentinel-b69ce2d6", SentinelStrings.ascii());
        assertEquals("", SentinelStrings.empty());
        assertArrayEquals(new char[] {'中', '文', 0, '\ud800', 'u', 'n', 'p', 'a', 'i', 'r', 'e', 'd',
                '\udfff', '\ud834', '\udd1e'}, SentinelStrings.utf16().toCharArray());
        assertEquals("lsp-duplicate-site-sentinel-733019", SentinelStrings.duplicateOne());
        assertEquals(SentinelStrings.duplicateOne(), SentinelStrings.duplicateTwo());
        String expected = "lsp-long-sentinel-c70b32e8:";
        for (int i = 0; i < 8; i++) {
            expected += "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        }
        assertEquals(expected, SentinelStrings.longLiteral());
        assertTrue(SentinelStrings.longLiteral().length() > 500);
    }
}
