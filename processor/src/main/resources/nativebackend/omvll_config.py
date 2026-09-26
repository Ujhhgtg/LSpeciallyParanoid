"""Selective O-MVLL 1.9.1 policy; no anti-hooking or detection passes."""
import omvll
import os
from functools import lru_cache


class LspConfig(omvll.ObfuscationConfig):
    def obfuscate_string(self, module, function, value):
        source = os.path.basename(module.source_filename)
        if source not in {"decoder.c", "runtime_guard.c", "apk_verify.c", "frida_guard.c"}:
            return False
        # LLVM also presents NUL-terminated byte arrays here. Leave ciphertext,
        # keys and format bytes alone; these templates' text literals are ASCII.
        if not value or len(value) > 4096 or any(c not in (9, 10, 13) and not 32 <= c < 127 for c in value):
            return False
        manifest = os.environ.get("LSP_NATIVE_STRING_MANIFEST")
        if manifest:
            # Private build input audit: never print literal values in Gradle output.
            with open(manifest, "a", encoding="ascii") as output:
                output.write(source + "\t" + value.hex() + "\n")
        print("LSP_OMVLL_SELECTED strings " + source, flush=True)
        return omvll.StringEncOptLocal()

    def flatten_cfg(self, module, function):
        selected = function.name in {"lsp_resolve", "crypto_aead_read", "lsp_guard_init", "lsp_apk_verify", "lsp_frida_check"}
        if selected:
            print("LSP_OMVLL_SELECTED flatten_cfg " + function.name, flush=True)
        return selected

    def obfuscate_arithmetic(self, module, function):
        selected = function.name == "lsp_resolve"
        if selected:
            print("LSP_OMVLL_SELECTED arithmetic " + function.name, flush=True)
        return omvll.ArithmeticOpt(2) if selected else False

    def anti_hooking(self, module, function):
        return False


@lru_cache(maxsize=1)
def omvll_get_config():
    return LspConfig()
