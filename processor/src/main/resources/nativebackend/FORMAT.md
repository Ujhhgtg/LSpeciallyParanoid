# Protected string format v1

One `NativeBuildSpec` is derived with HMAC-SHA-256 from 32 bytes of fresh build
entropy and the UTF-8 module/variant identity. Separate labeled HMAC derivations
produce each public name, the resource namespace, record IDs, and the literal and
resource ChaCha20-Poly1305 keys. Entropy is a private build input, not an embedded
seed. Fixed entropy is useful only in tests.

Each occurrence receives a distinct ID and nonce. IDs use the first eight bytes
of a domain-separated HMAC over the occurrence counter, interpreted big-endian,
with the high bit cleared for a literal or set for a resource. The registry checks collisions and advances the
counter if necessary. Each domain must have one registry/producer per build.
Its 12-byte nonce is `domain:u32le || occurrence:u64le`. Domain keys differ, and
each counter value is used at most once under its key.

The plaintext consists of raw little-endian UTF-16 code units, without a BOM,
terminator, replacement, or normalization. In particular, NUL and lone surrogate
code units are preserved. Each record is independently encrypted with RFC 8439
ChaCha20-Poly1305. Associated data is exactly:

```
formatVersion:u32le || domain:u32le || id:u64le || utf16Length:u32le
```

Ciphertext is followed by its 16-byte authentication tag. The native payload has
a compact table sorted by unsigned ID, containing ID, UTF-16 length, ciphertext
offset, nonce, and domain. Metadata is public. The decoder uses binary search,
checks all bounds, authenticates before reading plaintext, and constructs the
Java string with `NewString`. It erases the temporary native buffer and crypto
context with Monocypher's volatile-write `crypto_wipe`; it has no plaintext cache.
The current per-record bound is 1,048,576 UTF-16 code units.

`NativeRecordIO` is a separate private intermediate-file envelope using Java
`DataOutputStream` big-endian primitives: magic `0x4c535031`, version `i32`, build
ID via `writeUTF`, record count `i32`, then ID `i64`, domain `u8`, length `i32`,
12 nonce bytes, and exactly `2*length + 16` ciphertext/tag bytes for each record.
Readers reject a different build ID, duplicate IDs, invalid lengths/domains,
truncation, and trailing bytes. Keys and source plaintext are not serialized.

The key is present in the native binary and plaintext is returned to Java.
This format provides consistency checks and changes the extraction work per
build; it does not prevent a device owner from dumping runtime plaintext.

The optional O-MVLL 1.9.1 policy flattens `lsp_resolve` and Monocypher's
`crypto_aead_read`, and applies two arithmetic-obfuscation rounds to the resolver.
JNI registration, allocation, and Java exception handling remain outside the
selected transforms. Callback selection is not sufficient verification: private
compiler logs must also show that ControlFlowFlattening applied changes to both
`decoder.c` and `monocypher.c`, and Arithmetic applied changes to `decoder.c`.
The Python configuration is cached as one instance, as required by O-MVLL.
