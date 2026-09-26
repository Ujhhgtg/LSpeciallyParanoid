#ifndef LSP_APK_VERIFY_H
#define LSP_APK_VERIFY_H

#include <stddef.h>
#include <stdint.h>

/* fd stays owned by the caller. All operations use pread, preserving its offset.
 * Accepts APK Signature Scheme v2, exactly one signer, pinned leaf certificate.
 * Supports SHA-256/SHA-512 RSA PKCS#1/PSS (<=4096 bits) and ECDSA NIST curves.
 * Returns 1 on success, 0 for unsupported, malformed, untrusted or changed APKs.
 * No Java objects, package manager response or caller-provided path is trusted. */
int lsp_apk_verify(int fd, const uint8_t (*cert_sha256)[32], size_t cert_count);

/* Call only on the SAME fd after lsp_apk_verify succeeds. Returns a malloc buffer
 * owned by the caller. Rejects duplicate names, inconsistent local headers,
 * ZIP64, encryption, unsupported compression, >128 MiB entries and bad CRCs.
 * Stored entries and raw DEFLATE are supported. Returns 1 on success. */
int lsp_apk_read_entry(int fd, const char *name, uint8_t **out, size_t *len);

void lsp_sha256(const void *bytes, size_t len, uint8_t out[32]);

#endif
