package dev.ujhhgtg.lsparanoid.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import dev.ujhhgtg.lsparanoid.generated.LspBootstrap;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipFile;

/** Isolated negative test: signed APK stays intact, but the loaded library's build-id note changes. */
public final class IntegrityProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            byte[] library;
            try (ZipFile apk = new ZipFile(getApplicationInfo().sourceDir)) {
                try (java.io.InputStream input = apk.getInputStream(apk.getEntry("lib/arm64-v8a/" + LspBootstrap.libraryFileName))) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    for (int count; (count = input.read(buffer)) != -1;) bytes.write(buffer, 0, count);
                    library = bytes.toByteArray();
                }
            }
            changeBuildId(library);
            File copy = new File(getCacheDir(), LspBootstrap.libraryFileName);
            if (copy.exists() && !copy.delete()) throw new IllegalStateException("Cannot replace probe library");
            try (FileOutputStream output = new FileOutputStream(copy)) { output.write(library); }
            if (!copy.setWritable(false, false)) throw new IllegalStateException("Cannot protect probe library");
            Log.i("LSP_INTEGRITY_TEST", "ATTEMPT altered ELF note in isolated process");
            LspBootstrap.loadAbsolute(copy);
            Log.e("LSP_INTEGRITY_TEST", "UNEXPECTED_SUCCESS");
        } catch (Exception error) {
            Log.e("LSP_INTEGRITY_TEST", "JAVA_ERROR", error);
        }
        finish();
    }

    private static void changeBuildId(byte[] library) {
        ByteBuffer elf = ByteBuffer.wrap(library).order(ByteOrder.LITTLE_ENDIAN);
        int headers = Math.toIntExact(elf.getLong(32));
        int stride = Short.toUnsignedInt(elf.getShort(54));
        int count = Short.toUnsignedInt(elf.getShort(56));
        for (int i = 0; i < count; i++) {
            int header = headers + i * stride;
            if (elf.getInt(header) != 4) continue; // PT_NOTE
            int offset = Math.toIntExact(elf.getLong(header + 8));
            int end = offset + Math.toIntExact(elf.getLong(header + 32));
            while (offset + 12 <= end) {
                int nameSize = elf.getInt(offset), size = elf.getInt(offset + 4), type = elf.getInt(offset + 8);
                int name = offset + 12, data = (name + nameSize + 3) & ~3;
                if (nameSize == 4 && type == 3 && size > 0 && data + size <= end
                        && library[name] == 'G' && library[name + 1] == 'N' && library[name + 2] == 'U') {
                    library[data] ^= 1;
                    return;
                }
                offset = (data + size + 3) & ~3;
            }
        }
        throw new IllegalStateException("GNU build-id note missing from fixture ELF");
    }
}
