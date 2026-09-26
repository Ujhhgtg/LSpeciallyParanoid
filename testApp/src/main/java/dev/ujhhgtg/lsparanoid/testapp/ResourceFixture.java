package dev.ujhhgtg.lsparanoid.testapp;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import dev.ujhhgtg.lsparanoid.generated.LspBootstrap;
import dev.ujhhgtg.lsparanoid.runtime.LspResourceContext;
import java.util.Locale;

/** Keeps the resource adapter in the application's optimized call graph, as in a real app. */
public final class ResourceFixture {
    public static Resources localized(Context base, Locale locale) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(locale));
        LspBootstrap.loadInstalled();
        return new LspResourceContext(base.createConfigurationContext(configuration),
                LspBootstrap::decode, LspBootstrap.namespace).getResources();
    }
}
