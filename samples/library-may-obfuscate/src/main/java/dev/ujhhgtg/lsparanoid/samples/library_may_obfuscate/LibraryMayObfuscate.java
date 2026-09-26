package dev.ujhhgtg.lsparanoid.samples.library_may_obfuscate;

import android.util.Log;

import dev.ujhhgtg.lsparanoid.Obfuscate;

@Obfuscate
public class LibraryMayObfuscate {
    public static String TAG = "LibraryMayObfuscate";

    public static void log() {
        Log.d(TAG, "may obfuscated");
    }
}
