package dev.ujhhgtg.lsparanoid.testapp;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Resources;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.ujhhgtg.lsparanoid.generated.LspBootstrap;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NativeResourceTest {
    private Resources resources(Locale locale) {
        Context base = ApplicationProvider.getApplicationContext();
        return ResourceFixture.localized(base, locale);
    }
    @Test public void selectionThenDecodeThenFormat() {
        Resources english = resources(Locale.US);
        assertEquals("LSP_SENTINEL_GREETING Ada", english.getString(R.string.native_greeting, "Ada"));
        assertEquals("1 native file", english.getQuantityString(R.plurals.native_files, 1, 1));
        assertEquals("2 native files", english.getQuantityString(R.plurals.native_files, 2, 2));
        Resources chinese = resources(Locale.SIMPLIFIED_CHINESE);
        assertEquals("原生你好 Ada", chinese.getString(R.string.native_greeting, "Ada"));
        assertEquals("2 个文件", chinese.getQuantityString(R.plurals.native_files, 2, 2));
        assertEquals("LSP_SENTINEL_GREETING Bea", english.getString(R.string.native_greeting, "Bea"));
    }
    @Test public void arraysEscapesAndPublicPassThrough() {
        Resources resource = resources(Locale.US);
        assertArrayEquals(new String[]{"LSP_SENTINEL_ARRAY", "你好", ""}, resource.getStringArray(R.array.native_array));
        assertEquals("  quoted  \nline\tend", resource.getString(R.string.native_escaped));
        assertEquals("Leave styled visible", resource.getString(R.string.native_styled));
        assertTrue(resource.getText(R.string.native_styled) instanceof android.text.Spanned);
        assertEquals("fallback", resource.getText(0, "fallback"));
        Context base = ApplicationProvider.getApplicationContext();
        assertEquals(base.createConfigurationContext(resource.getConfiguration()).getString(android.R.string.ok), resource.getString(android.R.string.ok));
    }
    @Test public void alteredNativeIdsFailInsteadOfReadingOutOfBounds() {
        LspBootstrap.loadInstalled();
        try { LspBootstrap.decode(0); fail("Unknown ID must fail"); }
        catch (IllegalArgumentException expected) { }
    }
}
