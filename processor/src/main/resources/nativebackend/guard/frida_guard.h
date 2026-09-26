#ifndef LSP_FRIDA_GUARD_H
#define LSP_FRIDA_GUARD_H

/* Scan at bootstrap, then at most once per second while decoding. Aborts on detection. */
void lsp_frida_check(int force);

#endif
