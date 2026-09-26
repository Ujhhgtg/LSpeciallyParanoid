#define _POSIX_C_SOURCE 200809L
#include "apk_verify.h"
#include "vendor/bearssl/bearssl.h"
#include <errno.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

#define LSP_SIGN_BLOCK_MAX (16u * 1024u * 1024u)
#define LSP_ENTRY_MAX (128u * 1024u * 1024u)
#define LSP_CHUNK (1024u * 1024u)
#define LSP_MAX_ALGS 16

typedef struct { const uint8_t *p; size_t n; } span;
typedef struct {
    uint64_t size, cd, cd_size, eocd_at, signing_at;
    uint16_t entries;
    uint8_t *eocd;
    size_t eocd_size;
} apk_layout;
typedef struct { uint32_t id; span value; } algorithm_record;

static uint16_t le16(const uint8_t *p) { return (uint16_t)(p[0] | ((uint16_t)p[1] << 8)); }
static uint32_t le32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static uint64_t le64(const uint8_t *p) { return le32(p) | ((uint64_t)le32(p + 4) << 32); }
static void put32(uint8_t *p, uint32_t x) {
    for (unsigned i = 0; i < 4; ++i) p[i] = (uint8_t)(x >> (i * 8));
}
static int equal(const void *a, const void *b, size_t n) {
    const uint8_t *x = a, *y = b;
    unsigned d = 0;
    for (size_t i = 0; i < n; ++i) d |= x[i] ^ y[i];
    return d == 0;
}
static int read_at(int fd, void *dst, size_t len, uint64_t at) {
    if (at > INT64_MAX || len > (uint64_t)INT64_MAX - at) return 0;
    uint8_t *p = dst;
    while (len) {
        ssize_t n = pread(fd, p, len, (off_t)at);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        p += n; len -= (size_t)n; at += (size_t)n;
    }
    return 1;
}
static int take(span *s, size_t n, span *out) {
    if (n > s->n) return 0;
    out->p = s->p; out->n = n; s->p += n; s->n -= n;
    return 1;
}
static int lp(span *s, span *out) {
    if (s->n < 4) return 0;
    uint32_t n = le32(s->p);
    s->p += 4; s->n -= 4;
    return take(s, n, out);
}

void lsp_sha256(const void *bytes, size_t len, uint8_t out[32]) {
    br_sha256_context c;
    br_sha256_init(&c); br_sha256_update(&c, bytes, len); br_sha256_out(&c, out);
}

static int layout_read(int fd, apk_layout *a) {
    struct stat st;
    memset(a, 0, sizeof(*a));
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size < 22) return 0;
    a->size = (uint64_t)st.st_size;
    size_t n = a->size < 65557 ? (size_t)a->size : 65557;
    uint8_t *tail = malloc(n);
    if (!tail) return 0;
    if (!read_at(fd, tail, n, a->size - n)) { free(tail); return 0; }
    int found = 0;
    for (size_t i = n - 22 + 1; i-- > 0;) {
        if (le32(tail + i) != 0x06054b50 || i + 22u + le16(tail + i + 20) != n) continue;
        const uint8_t *e = tail + i;
        if (le16(e + 4) || le16(e + 6) || le16(e + 8) != le16(e + 10) ||
            le16(e + 10) == 0xffff || le32(e + 12) == UINT32_MAX || le32(e + 16) == UINT32_MAX) break;
        a->eocd_at = a->size - n + i;
        a->cd = le32(e + 16); a->cd_size = le32(e + 12); a->entries = le16(e + 10);
        if (a->cd + a->cd_size != a->eocd_at) break;
        uint8_t locator[4];
        if (a->eocd_at >= 20 && (!read_at(fd, locator, 4, a->eocd_at - 20) || le32(locator) == 0x07064b50)) break;
        a->eocd_size = n - i;
        memmove(tail, e, a->eocd_size);
        a->eocd = tail; found = 1; break;
    }
    if (!found) free(tail);
    return found;
}

/* DER framing only: crypto and public-key decoding are BearSSL operations.
 * This locates the certificate's exact SPKI bytes, so accepting a pinned leaf
 * alongside an attacker's separately supplied public key is impossible. */
static int der(span *s, unsigned tag, span *value, span *whole) {
    const uint8_t *start = s->p;
    size_t initial = s->n;
    if (s->n < 2 || s->p[0] != tag) return 0;
    size_t n = s->p[1], header = 2;
    if (n & 128) {
        size_t count = n & 127;
        if (!count || count > sizeof(size_t) || count > s->n - 2 || s->p[2] == 0) return 0;
        n = 0;
        for (size_t i = 0; i < count; ++i) {
            if (n > SIZE_MAX >> 8) return 0;
            n = (n << 8) | s->p[2 + i];
        }
        if (n < 128) return 0;
        header += count;
    }
    if (header > s->n || n > s->n - header) return 0;
    s->p += header; s->n -= header;
    if (!take(s, n, value)) return 0;
    if (whole) { whole->p = start; whole->n = initial - s->n; }
    return 1;
}
static int certificate_spki(span cert, span *spki) {
    span seq, tbs, unused;
    if (!der(&cert, 0x30, &seq, NULL) || cert.n || !der(&seq, 0x30, &tbs, NULL)) return 0;
    if (!der(&seq, 0x30, &unused, NULL) || !der(&seq, 0x03, &unused, NULL) || seq.n) return 0;
    if (tbs.n && tbs.p[0] == 0xa0 && !der(&tbs, 0xa0, &unused, NULL)) return 0;
    if (!der(&tbs, 0x02, &unused, NULL)) return 0;
    for (unsigned i = 0; i < 4; ++i) if (!der(&tbs, 0x30, &unused, NULL)) return 0;
    return der(&tbs, 0x30, &unused, spki);
}

static size_t hash_size(uint32_t id) {
    switch (id) {
        case 0x0101: case 0x0103: case 0x0201: return 32;
        case 0x0102: case 0x0104: case 0x0202: return 64;
        default: return 0;
    }
}
static const br_hash_class *hash_class(size_t n) { return n == 32 ? &br_sha256_vtable : &br_sha512_vtable; }
static int records(span s, algorithm_record out[LSP_MAX_ALGS], size_t *count, int digest) {
    *count = 0;
    while (s.n) {
        span r, v;
        if (*count == LSP_MAX_ALGS || !lp(&s, &r) || r.n < 4) return 0;
        uint32_t id = le32(r.p); r.p += 4; r.n -= 4;
        size_t h = hash_size(id);
        if (!h || !lp(&r, &v) || r.n || !v.n || (digest && v.n != h)) return 0;
        for (size_t i = 0; i < *count; ++i) if (out[i].id == id) return 0;
        out[*count].id = id; out[*count].value = v; ++*count;
    }
    return *count != 0;
}
static int check_signature(const br_x509_pkey *pk, uint32_t id, span sig, span signed_data) {
    uint8_t digest[64], recovered[64];
    size_t n = hash_size(id);
    const br_hash_class *hf = hash_class(n);
    br_hash_compat_context hc;
    hf->init(&hc.vtable); hf->update(&hc.vtable, signed_data.p, signed_data.n); hf->out(&hc.vtable, digest);
    if (id == 0x0101 || id == 0x0102) {
        return pk->key_type == BR_KEYTYPE_RSA &&
            br_rsa_i31_pss_vrfy(sig.p, sig.n, hf, hf, digest, n, &pk->key.rsa) == 1;
    }
    if (id == 0x0103 || id == 0x0104) {
        const unsigned char *oid = n == 32 ? BR_HASH_OID_SHA256 : BR_HASH_OID_SHA512;
        return pk->key_type == BR_KEYTYPE_RSA &&
            br_rsa_i31_pkcs1_vrfy(sig.p, sig.n, oid, n, &pk->key.rsa, recovered) == 1 && equal(digest, recovered, n);
    }
    if (id == 0x0201 || id == 0x0202) {
        return pk->key_type == BR_KEYTYPE_EC &&
            br_ecdsa_i31_vrfy_asn1(&br_ec_prime_i31, digest, n, &pk->key.ec, sig.p, sig.n) == 1;
    }
    return 0;
}

static int content_digest(int fd, const apk_layout *a, size_t n, uint8_t out[64]) {
    const br_hash_class *hf = hash_class(n);
    uint64_t sizes[3] = {a->signing_at, a->cd_size, a->eocd_size};
    uint64_t offsets[3] = {0, a->cd, a->eocd_at};
    uint64_t chunks = 0;
    for (unsigned i = 0; i < 3; ++i) chunks += (sizes[i] + LSP_CHUNK - 1) / LSP_CHUNK;
    if (chunks > UINT32_MAX || a->signing_at > UINT32_MAX) return 0;
    uint8_t *buf = malloc(LSP_CHUNK), header[5], one[64];
    if (!buf) return 0;
    br_hash_compat_context top, part;
    hf->init(&top.vtable);
    header[0] = 0x5a; put32(header + 1, (uint32_t)chunks);
    hf->update(&top.vtable, header, sizeof(header));
    int ok = 1;
    for (unsigned i = 0; i < 3 && ok; ++i) {
        for (uint64_t at = 0; at < sizes[i];) {
            size_t length = (size_t)(sizes[i] - at > LSP_CHUNK ? LSP_CHUNK : sizes[i] - at);
            if (i == 2) {
                /* EOCD is at most 65557 bytes; it always fits in one chunk. */
                memcpy(buf, a->eocd, length); put32(buf + 16, (uint32_t)a->signing_at);
            } else if (!read_at(fd, buf, length, offsets[i] + at)) { ok = 0; break; }
            hf->init(&part.vtable);
            header[0] = 0xa5; put32(header + 1, (uint32_t)length);
            hf->update(&part.vtable, header, sizeof(header));
            hf->update(&part.vtable, buf, length); hf->out(&part.vtable, one);
            hf->update(&top.vtable, one, n);
            at += length;
        }
    }
    if (ok) hf->out(&top.vtable, out);
    free(buf);
    return ok;
}

static int verify_signer(int fd, const apk_layout *a, span v2, const uint8_t (*pins)[32], size_t pin_count) {
    span signers, signer, signed_data, signatures, public_key;
    if (!lp(&v2, &signers) || v2.n || !lp(&signers, &signer) || signers.n) return 0;
    if (!lp(&signer, &signed_data) || !lp(&signer, &signatures) || !lp(&signer, &public_key) || signer.n) return 0;
    span data = signed_data, digests, certs, attrs, leaf, cert_spki;
    if (!lp(&data, &digests) || !lp(&data, &certs) || !lp(&data, &attrs) || !lp(&certs, &leaf)) return 0;
    /* Current AOSP apksig appends one empty length-prefixed field. Older
     * signers omit it. Both forms are signature-covered; reject other tails. */
    if (data.n && (data.n != 4 || le32(data.p) != 0)) return 0;
    uint8_t fingerprint[32];
    lsp_sha256(leaf.p, leaf.n, fingerprint);
    int pinned = 0;
    for (size_t i = 0; i < pin_count; ++i) pinned |= equal(fingerprint, pins[i], 32);
    if (!pinned || !certificate_spki(leaf, &cert_spki) || cert_spki.n != public_key.n ||
        !equal(cert_spki.p, public_key.p, public_key.n)) return 0;
    /* Remaining chain elements are signed metadata, but are never trust anchors. */
    while (certs.n) {
        span cert, cert_seq;
        if (!lp(&certs, &cert) || !der(&cert, 0x30, &cert_seq, NULL) || cert.n) return 0;
    }
    uint32_t attribute_ids[64]; size_t attribute_count = 0;
    while (attrs.n) {
        span attr;
        if (attribute_count == 64 || !lp(&attrs, &attr) || attr.n < 4) return 0;
        uint32_t id = le32(attr.p);
        for (size_t i = 0; i < attribute_count; ++i) if (attribute_ids[i] == id) return 0;
        attribute_ids[attribute_count++] = id;
        /* v3 stripping-protection metadata does not authenticate v2 content;
         * we always require and validate v2, with the exact certificate pin. */
    }
    br_x509_decoder_context cert_decoder;
    br_x509_decoder_init(&cert_decoder, NULL, NULL);
    br_x509_decoder_push(&cert_decoder, leaf.p, leaf.n);
    br_x509_pkey *pk = br_x509_decoder_get_pkey(&cert_decoder);
    if (br_x509_decoder_last_error(&cert_decoder) || !pk) return 0;
    algorithm_record sig[LSP_MAX_ALGS], dig[LSP_MAX_ALGS]; size_t ns, nd;
    if (!records(signatures, sig, &ns, 0) || !records(digests, dig, &nd, 1) || ns != nd) return 0;
    uint8_t d256[64], d512[64]; int got256 = 0, got512 = 0;
    for (size_t i = 0; i < ns; ++i) {
        if (sig[i].id != dig[i].id || !check_signature(pk, sig[i].id, sig[i].value, signed_data)) return 0;
        size_t h = hash_size(sig[i].id);
        if (h == 32 && !got256) { if (!content_digest(fd, a, 32, d256)) return 0; got256 = 1; }
        if (h == 64 && !got512) { if (!content_digest(fd, a, 64, d512)) return 0; got512 = 1; }
        if (!equal(dig[i].value.p, h == 32 ? d256 : d512, h)) return 0;
    }
    return 1;
}

int lsp_apk_verify(int fd, const uint8_t (*pins)[32], size_t pin_count) {
    if (!pins || !pin_count) return 0;
    apk_layout a;
    if (!layout_read(fd, &a)) return 0;
    uint8_t footer[24], *block = NULL;
    int ok = 0;
    if (a.cd < 32 || !read_at(fd, footer, sizeof(footer), a.cd - sizeof(footer)) ||
        memcmp(footer + 8, "APK Sig Block 42", 16)) goto done;
    uint64_t size = le64(footer);
    if (size < 24 || size > LSP_SIGN_BLOCK_MAX || size > a.cd - 8) goto done;
    a.signing_at = a.cd - size - 8;
    block = malloc((size_t)size + 8);
    if (!block || !read_at(fd, block, (size_t)size + 8, a.signing_at) || le64(block) != size) goto done;
    span pairs = {block + 8, (size_t)size - 24}, v2 = {NULL, 0};
    uint32_t ids[64]; size_t id_count = 0;
    while (pairs.n) {
        span pair;
        if (pairs.n < 8 || id_count == 64) goto done;
        uint64_t length = le64(pairs.p); pairs.p += 8; pairs.n -= 8;
        if (length < 4 || length > pairs.n || !take(&pairs, (size_t)length, &pair)) goto done;
        uint32_t id = le32(pair.p);
        for (size_t i = 0; i < id_count; ++i) if (ids[i] == id) goto done;
        ids[id_count++] = id;
        if (id == 0x7109871a) { v2.p = pair.p + 4; v2.n = pair.n - 4; }
    }
    if (v2.p) ok = verify_signer(fd, &a, v2, pins, pin_count);
done:
    free(block); free(a.eocd);
    return ok;
}

int lsp_apk_read_entry(int fd, const char *name, uint8_t **out, size_t *len) {
    if (!name || !out || !len) return 0;
    *out = NULL; *len = 0;
    apk_layout a;
    if (!layout_read(fd, &a)) return 0;
    size_t wanted = strlen(name);
    uint8_t central[46], local[30], *entry_name = NULL, *compressed = NULL, *bytes = NULL;
    uint32_t compressed_size = 0, plain_size = 0, crc = 0, local_offset = 0;
    uint16_t flags = 0, method = 0;
    uint64_t at = a.cd;
    int found = 0, ok = 0;
    for (uint32_t i = 0; i < a.entries; ++i) {
        if (at > a.eocd_at || a.eocd_at - at < sizeof(central) || !read_at(fd, central, sizeof(central), at) ||
            le32(central) != 0x02014b50 || le16(central + 34)) goto done;
        size_t name_size = le16(central + 28);
        uint64_t record_size = 46u + name_size + le16(central + 30) + le16(central + 32);
        if (record_size > a.eocd_at - at) goto done;
        if (name_size == wanted) {
            uint8_t *next = realloc(entry_name, name_size ? name_size : 1);
            if (!next) goto done;
            entry_name = next;
            if (!read_at(fd, entry_name, name_size, at + 46)) goto done;
            if (equal(entry_name, name, wanted)) {
                if (found) goto done;
                found = 1; flags = le16(central + 8); method = le16(central + 10);
                crc = le32(central + 16); compressed_size = le32(central + 20); plain_size = le32(central + 24);
                local_offset = le32(central + 42);
            }
        }
        at += record_size;
    }
    if (!found || at != a.eocd_at || (flags & (1u | 64u | 8192u)) || (method != 0 && method != 8) ||
        compressed_size > LSP_ENTRY_MAX || plain_size > LSP_ENTRY_MAX || local_offset >= a.cd ||
        a.cd - local_offset < sizeof(local) || !read_at(fd, local, sizeof(local), local_offset) ||
        le32(local) != 0x04034b50 || le16(local + 6) != flags || le16(local + 8) != method ||
        le16(local + 26) != wanted) goto done;
    uint64_t data_at = (uint64_t)local_offset + 30u + le16(local + 26) + le16(local + 28);
    if (data_at > a.cd || compressed_size > a.cd - data_at ||
        !read_at(fd, entry_name, wanted, (uint64_t)local_offset + 30u) || !equal(entry_name, name, wanted)) goto done;
    if (!(flags & 8) && (le32(local + 14) != crc || le32(local + 18) != compressed_size || le32(local + 22) != plain_size)) goto done;
    compressed = malloc(compressed_size ? compressed_size : 1); bytes = malloc(plain_size ? plain_size : 1);
    if (!compressed || !bytes || !read_at(fd, compressed, compressed_size, data_at)) goto done;
    if (method == 0) {
        if (compressed_size != plain_size) goto done;
        memcpy(bytes, compressed, plain_size);
    } else {
        z_stream z; memset(&z, 0, sizeof(z));
        z.next_in = compressed; z.avail_in = compressed_size;
        z.next_out = bytes; z.avail_out = plain_size ? plain_size : 1;
        if (inflateInit2(&z, -MAX_WBITS) != Z_OK) goto done;
        int result = inflate(&z, Z_FINISH);
        int valid = result == Z_STREAM_END && z.total_in == compressed_size && z.total_out == plain_size;
        inflateEnd(&z);
        if (!valid) goto done;
    }
    if ((uint32_t)crc32(0, bytes, plain_size) != crc) goto done;
    *out = bytes; *len = plain_size; bytes = NULL; ok = 1;
done:
    free(bytes); free(compressed); free(entry_name); free(a.eocd);
    return ok;
}
