package dev.ujhhgtg.lsparanoid.testapp;

import static org.junit.Assert.assertEquals;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ujhhgtg.lsparanoid.generated.LspBootstrap;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Informational fixture timings; deliberately no device-dependent performance threshold. */
@RunWith(AndroidJUnit4.class)
public final class NativePerformanceTest {
    @Test public void reportDecoderCosts() {
        boolean cold = !LspBootstrap.isLoaded();
        long start = SystemClock.elapsedRealtimeNanos();
        LspBootstrap.loadInstalled();
        long loadNanos = SystemClock.elapsedRealtimeNanos() - start;
        for (int i = 0; i < 2000; i++) SentinelStrings.ascii();
        int checksum = 0;
        final int count = 20000;
        start = SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < count; i++) checksum += SentinelStrings.ascii().length();
        long shortNanos = SystemClock.elapsedRealtimeNanos() - start;
        assertEquals(count * 36, checksum);
        checksum = 0;
        start = SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < count; i++) checksum += SentinelStrings.longLiteral().length();
        long longNanos = SystemClock.elapsedRealtimeNanos() - start;
        assertEquals(count * 523, checksum);
        Bundle result = new Bundle();
        result.putString("lsp_benchmark", "cold_load=" + cold + ", load_ns=" + loadNanos
                + ", short_mean_ns=" + shortNanos / count + ", long_mean_ns=" + longNanos / count
                + ", iterations=" + count);
        InstrumentationRegistry.getInstrumentation().sendStatus(2, result);
    }
}
