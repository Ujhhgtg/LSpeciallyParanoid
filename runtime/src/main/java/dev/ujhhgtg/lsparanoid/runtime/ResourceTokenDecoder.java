package dev.ujhhgtg.lsparanoid.runtime;

import java.util.Objects;
import java.util.function.LongFunction;

/** Strict, build-scoped tokens; host strings and tokens from another build are passed through. */
public final class ResourceTokenDecoder {
    private final String prefix;
    private final String namespace;
    private final LongFunction<String> decoder;

    public ResourceTokenDecoder(LongFunction<String> decoder, String namespace) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        if (namespace == null || !namespace.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Resource namespace must be 32 lowercase hex characters");
        }
        this.namespace = namespace;
        prefix = "~lsp1:" + namespace + ":";
    }

    public boolean isOwnToken(CharSequence value) {
        if (value == null) return false;
        String text = value.toString();
        if (!text.startsWith("~lsp")) return false;
        int separator = text.indexOf(':', 4);
        return separator >= 0 && text.startsWith(namespace + ":", separator + 1);
    }

    public CharSequence decode(CharSequence value) {
        if (!isOwnToken(value)) return value;
        String token = value.toString();
        if (!token.startsWith(prefix) || token.length() != prefix.length() + 17 || token.charAt(token.length() - 1) != '~') {
            throw corrupt();
        }
        long id = 0;
        for (int i = prefix.length(); i < prefix.length() + 16; i++) {
            char c = token.charAt(i);
            int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
            if (digit < 0) throw corrupt();
            id = (id << 4) | digit;
        }
        // Resource and literal registries occupy disjoint ID domains.
        if (id >= 0) throw corrupt();
        return Objects.requireNonNull(decoder.apply(id), "Resource decoder returned null");
    }

    public String decode(String value) {
        return (String) decode((CharSequence) value);
    }

    private IllegalArgumentException corrupt() {
        return new IllegalArgumentException("Malformed protected resource token for this build");
    }
}
