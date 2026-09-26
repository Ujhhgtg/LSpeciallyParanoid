package dev.ujhhgtg.lsparanoid.runtime;

import android.content.res.Resources;
import android.content.res.Configuration;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Movie;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.LongFunction;

/**
 * String-family adapter, installed beneath application text transformations. Android still selects
 * the configuration, plural category and array entries. This does not intercept XML/TypedArray reads.
 * Recreate at a configuration boundary; never use this as a global resource or locale cache.
 */
@SuppressWarnings("deprecation")
public final class LspResources extends Resources {
    private static final CharSequence MISSING_TEXT = new StringBuilder();
    private final Resources delegate;
    private final ResourceTokenDecoder tokens;

    public LspResources(Resources delegate, LongFunction<String> decoder, String namespace) {
        super(Objects.requireNonNull(delegate, "delegate").getAssets(), delegate.getDisplayMetrics(), delegate.getConfiguration());
        this.delegate = delegate;
        tokens = new ResourceTokenDecoder(decoder, namespace);
    }

    @Override public CharSequence getText(int id) { return tokens.decode(delegate.getText(id)); }
    @Override public CharSequence getText(int id, CharSequence defaultValue) {
        CharSequence value = delegate.getText(id, MISSING_TEXT);
        // Use a private marker: a valid resource value may be identical to the caller's default.
        // A caller-owned fallback, including null or a token-shaped value, is returned untouched.
        return value == MISSING_TEXT ? defaultValue : tokens.decode(value);
    }
    @Override public String getString(int id) { return tokens.decode(delegate.getString(id)); }
    @Override public String getString(int id, Object... formatArgs) {
        String raw = delegate.getString(id);
        if (!tokens.isOwnToken(raw)) return delegate.getString(id, formatArgs);
        return String.format(delegate.getConfiguration().getLocales().get(0), tokens.decode(raw), formatArgs);
    }
    @Override public CharSequence getQuantityText(int id, int quantity) {
        return tokens.decode(delegate.getQuantityText(id, quantity));
    }
    @Override public String getQuantityString(int id, int quantity) {
        return tokens.decode(delegate.getQuantityString(id, quantity));
    }
    @Override public String getQuantityString(int id, int quantity, Object... formatArgs) {
        String raw = delegate.getQuantityString(id, quantity);
        if (!tokens.isOwnToken(raw)) return delegate.getQuantityString(id, quantity, formatArgs);
        return String.format(delegate.getConfiguration().getLocales().get(0), tokens.decode(raw), formatArgs);
    }
    @Override public CharSequence[] getTextArray(int id) {
        CharSequence[] values = delegate.getTextArray(id).clone();
        for (int i = 0; i < values.length; i++) values[i] = tokens.decode(values[i]);
        return values;
    }
    @Override public String[] getStringArray(int id) {
        String[] values = delegate.getStringArray(id).clone();
        for (int i = 0; i < values.length; i++) values[i] = tokens.decode(values[i]);
        return values;
    }

    // Preserve delegate behavior for non-string reads used alongside LocalResources. Framework
    // getValue/TypedArray callers are explicitly outside the protection contract and are reported.
    @Override public Configuration getConfiguration() { return delegate.getConfiguration(); }
    @Override public DisplayMetrics getDisplayMetrics() { return delegate.getDisplayMetrics(); }
    @Override public boolean getBoolean(int id) { return delegate.getBoolean(id); }
    @Override public int getColor(int id) { return delegate.getColor(id); }
    @Override public int getColor(int id, Theme theme) { return delegate.getColor(id, theme); }
    @Override public ColorStateList getColorStateList(int id) { return delegate.getColorStateList(id); }
    @Override public ColorStateList getColorStateList(int id, Theme theme) { return delegate.getColorStateList(id, theme); }
    @Override public float getDimension(int id) { return delegate.getDimension(id); }
    @Override public int getDimensionPixelOffset(int id) { return delegate.getDimensionPixelOffset(id); }
    @Override public int getDimensionPixelSize(int id) { return delegate.getDimensionPixelSize(id); }
    @Override public Drawable getDrawable(int id) { return delegate.getDrawable(id); }
    @Override public Drawable getDrawable(int id, Theme theme) { return delegate.getDrawable(id, theme); }
    @Override public Drawable getDrawableForDensity(int id, int density) { return delegate.getDrawableForDensity(id, density); }
    @Override public Drawable getDrawableForDensity(int id, int density, Theme theme) { return delegate.getDrawableForDensity(id, density, theme); }
    @Override public Typeface getFont(int id) { return delegate.getFont(id); }
    @Override public float getFraction(int id, int base, int pbase) { return delegate.getFraction(id, base, pbase); }
    @Override public int getIdentifier(String name, String defType, String defPackage) { return delegate.getIdentifier(name, defType, defPackage); }
    @Override public int[] getIntArray(int id) { return delegate.getIntArray(id); }
    @Override public int getInteger(int id) { return delegate.getInteger(id); }
    @Override public XmlResourceParser getLayout(int id) { return delegate.getLayout(id); }
    @Override public Movie getMovie(int id) { return delegate.getMovie(id); }
    @Override public String getResourceEntryName(int id) { return delegate.getResourceEntryName(id); }
    @Override public String getResourceName(int id) { return delegate.getResourceName(id); }
    @Override public String getResourcePackageName(int id) { return delegate.getResourcePackageName(id); }
    @Override public String getResourceTypeName(int id) { return delegate.getResourceTypeName(id); }
    @Override public void getValue(int id, TypedValue out, boolean resolveRefs) { delegate.getValue(id, out, resolveRefs); }
    @Override public void getValue(String name, TypedValue out, boolean resolveRefs) { delegate.getValue(name, out, resolveRefs); }
    @Override public void getValueForDensity(int id, int density, TypedValue out, boolean resolveRefs) { delegate.getValueForDensity(id, density, out, resolveRefs); }
    @Override public XmlResourceParser getXml(int id) { return delegate.getXml(id); }
    @Override public XmlResourceParser getAnimation(int id) { return delegate.getAnimation(id); }
    @Override public TypedArray obtainAttributes(AttributeSet set, int[] attrs) { return delegate.obtainAttributes(set, attrs); }
    @Override public TypedArray obtainTypedArray(int id) { return delegate.obtainTypedArray(id); }
    @Override public InputStream openRawResource(int id) { return delegate.openRawResource(id); }
    @Override public InputStream openRawResource(int id, TypedValue out) { return delegate.openRawResource(id, out); }
    @Override public AssetFileDescriptor openRawResourceFd(int id) { return delegate.openRawResourceFd(id); }
}
