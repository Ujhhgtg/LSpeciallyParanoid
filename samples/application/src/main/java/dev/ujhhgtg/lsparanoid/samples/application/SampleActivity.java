package dev.ujhhgtg.lsparanoid.samples.application;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import dev.ujhhgtg.lsparanoid.Obfuscate;

@Obfuscate
public class SampleActivity extends Activity {
    public static String TAG = "SampleActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
    }
}
