package dev.ujhhgtg.lsparanoid.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.function.LongFunction;

/** Resource-only context for a localization boundary; keep the original Activity/window context. */
public final class LspResourceContext extends ContextWrapper {
    private final LongFunction<String> decoder;
    private final String namespace;
    private final LspResources resources;

    public LspResourceContext(Context base, LongFunction<String> decoder, String namespace) {
        super(base);
        this.decoder = decoder;
        this.namespace = namespace;
        resources = new LspResources(base.getResources(), decoder, namespace);
    }

    @Override public Resources getResources() { return resources; }

    @Override public Context createConfigurationContext(Configuration configuration) {
        return new LspResourceContext(getBaseContext().createConfigurationContext(configuration), decoder, namespace);
    }
}
