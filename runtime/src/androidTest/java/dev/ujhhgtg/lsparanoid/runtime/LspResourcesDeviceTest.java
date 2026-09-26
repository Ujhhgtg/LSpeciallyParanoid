package dev.ujhhgtg.lsparanoid.runtime;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Locale;
import java.util.function.LongFunction;
import org.junit.Test;
import static org.junit.Assert.*;

/** Framework-dependent checks: run on both the API floor and a current arm64 Android device. */
public class LspResourcesDeviceTest {
    private static final String NS = "0123456789abcdef0123456789abcdef";
    private static final LongFunction<String> DECODE = id -> switch ((int) id) {
        case 1 -> "%1$s: %2$.1f";
        case 2 -> "Default";
        case 3 -> "%1$d file";
        case 4 -> "%1$d files";
        case 5 -> "Français";
        default -> throw new IllegalArgumentException("Unknown fixture token");
    };

    private Context context(Locale locale) {
        Context base = InstrumentationRegistry.getInstrumentation().getContext();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocales(new LocaleList(locale));
        return base.createConfigurationContext(config);
    }
    private int id(Context context, String type, String name) {
        int result = context.getResources().getIdentifier("lsp_test_" + name, type, context.getPackageName());
        assertNotEquals(0, result);
        return result;
    }

    @Test public void usesSelectedResourceLocaleAndDecodesBeforeFormatting() {
        Context base = context(Locale.FRANCE);
        Resources wrapped = new LspResources(base.getResources(), DECODE, NS);
        assertEquals("Value: 1,5", wrapped.getString(id(base, "string", "format"), "Value", 1.5));
        assertEquals("Français", wrapped.getString(id(base, "string", "locale")));
        assertEquals("Host untouched", wrapped.getString(id(base, "string", "host"), "untouched"));
        assertTrue(wrapped.getString(id(base, "string", "other_build")).contains("ffffffff"));
    }

    @Test public void pluralArraysDefaultsAndNonStringValuesPreserveBehavior() {
        Context base = context(Locale.US);
        Resources wrapped = new LspResources(base.getResources(), DECODE, NS);
        int plural = id(base, "plurals", "plural");
        assertEquals("1 file", wrapped.getQuantityString(plural, 1, 1));
        assertEquals("2 files", wrapped.getQuantityString(plural, 2, 2));
        assertEquals("%1$d file", wrapped.getQuantityText(plural, 1));
        int array = id(base, "array", "array");
        assertArrayEquals(new String[]{"Default", "Host"}, wrapped.getStringArray(array));
        assertArrayEquals(new CharSequence[]{"Default", "Host"}, wrapped.getTextArray(array));
        assertNull(wrapped.getText(0, null));
        CharSequence defaultValue = new StringBuilder("~lsp1:" + NS + ":8000000000000001~");
        assertSame(defaultValue, wrapped.getText(0, defaultValue));
        int existing = id(base, "string", "locale");
        CharSequence matchingDefault = base.getResources().getText(existing);
        assertEquals("Default", wrapped.getText(existing, matchingDefault));
        assertEquals(42, wrapped.getInteger(id(base, "integer", "integer")));
    }

    @Test public void resourceContextRecreatesWrapperOnExplicitConfigurationChange() {
        Context base = context(Locale.US);
        Context wrapped = new LspResourceContext(base, DECODE, NS);
        int string = id(base, "string", "locale");
        assertEquals("Default", wrapped.getString(string));
        Configuration french = new Configuration(base.getResources().getConfiguration());
        french.setLocales(new LocaleList(Locale.FRANCE));
        assertEquals("Français", wrapped.createConfigurationContext(french).getString(string));
        assertEquals("Default", wrapped.getString(string));
    }
}
