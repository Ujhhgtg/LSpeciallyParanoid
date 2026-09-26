# Vendored Monocypher 4.0.3

Unmodified `src/monocypher.c`, `src/monocypher.h`, and `LICENCE.md` from
https://github.com/LoupVaillant/Monocypher/tree/4.0.3 (retrieved 2026-09-26).
Redistributed using upstream's CC0 option of the BSD 2-clause / CC0 dual license.
Source copyright notices and the complete dual-license text are retained.
Generated decoder directories also receive `LICENCE.md`.

SHA-256:

- monocypher.c: `f1f838cdd483bdebe0df0ff5c5ed60535e496f769c6a2f933ac4c0b114207123`
- monocypher.h: `fcaf6ed771358bb4f40fba016f6518ae86ec02b1b877d2cc35ad92d3a26fd7b3`
- LICENCE.md: `a5781770269d2516e52ba4863f790c10a16da4089a1e81823aee19ff1e9026b0`

Only `crypto_aead_init_ietf`, `crypto_aead_read`, and `crypto_wipe` are used.
Function/data sections and linker garbage collection remove unused primitives.
The incremental interface is initialized fresh for each record; its first record
matches RFC 8439 ChaCha20-Poly1305. JVM encryption uses the JDK implementation.
Tests compare them on RFC 8439's known-answer vector and generated UTF-16 values.

Upstream API: https://monocypher.org/manual/aead
Known issues: https://monocypher.org/bugs (checked 2026-09-26).
No local cryptographic algorithm modifications are maintained.
