package dev.ujhhgtg.lsparanoid.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ResourceTokenDecoderTest {
    private static final String NS = "0123456789abcdef0123456789abcdef";
    private static final String TOKEN = "~lsp1:" + NS + ":fedcba9876543210~";

    @Test void preservesHostValuesIdentityAndNull() {
        ResourceTokenDecoder decoder = new ResourceTokenDecoder(id -> { throw new AssertionError(); }, NS);
        StringBuilder spannedStandIn = new StringBuilder("host text");
        assertSame(spannedStandIn, decoder.decode(spannedStandIn));
        assertNull(decoder.decode((CharSequence) null));
        String otherBuild = TOKEN.replace(NS, "f".repeat(32));
        assertSame(otherBuild, decoder.decode(otherBuild));
    }

    @Test void decodesUnsignedResourceIdsOnceWithoutRecursiveParsing() {
        AtomicInteger calls = new AtomicInteger();
        ResourceTokenDecoder decoder = new ResourceTokenDecoder(id -> {
            assertEquals(0xfedcba9876543210L, id);
            calls.incrementAndGet();
            return TOKEN;
        }, NS);
        assertEquals(TOKEN, decoder.decode(TOKEN));
        assertEquals(1, calls.get());
    }

    @Test void rejectsCorruptOwnTokensAndLiteralDomain() {
        ResourceTokenDecoder decoder = new ResourceTokenDecoder(id -> "ok", NS);
        for (String invalid : new String[]{TOKEN + "x", TOKEN.substring(0, TOKEN.length() - 1), TOKEN.replace("fedc", "FEDC"), TOKEN.replace("fedc", "0edc"), TOKEN.replace("lsp1", "lsp2")}) {
            assertThrows(IllegalArgumentException.class, () -> decoder.decode(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new ResourceTokenDecoder(id -> "", "wrong"));
        assertThrows(NullPointerException.class, () -> new ResourceTokenDecoder(id -> null, NS).decode(TOKEN));
    }
}
