"""Selective O-MVLL 1.9.1 policy; no anti-hooking or detection passes."""
import omvll
from functools import lru_cache


class LspConfig(omvll.ObfuscationConfig):
    def flatten_cfg(self, module, function):
        selected = function.name in {"lsp_resolve", "crypto_aead_read"}
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
