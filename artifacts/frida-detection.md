# Frida detection — source audit and validation

Date: 2026-09-27. Protector version: 0.13.1.

Native protection now aborts on a matched Frida indicator. WeKit's existing build
switch still controls whether native protection is present: debug/test defaults
are unprotected, release defaults are protected. This does not ban root, LSPosed,
Zygisk, generic GLib thread names, anonymous executable memory, or ordinary JIT caches.

## Source actually inspected

The root Frida repository is a build/release repository; the relevant implementations
are in its pinned `frida-core` and `frida-gum` submodules. Inspected checkouts are
retained locally under ignored `build/frida/source`.

| Source | Revision | Findings used in the implementation |
| --- | --- | --- |
| [frida/frida](https://github.com/frida/frida/tree/63b3233f29c41fa274d634909cab7aaba81e4b32) | `63b3233f` | Pins core `b66a485f` and Gum `02bc3d80`. |
| [Gum scheduler](https://github.com/frida/frida-gum/blob/02bc3d805455f79eef1de1acbbfe72328d477756/bindings/gumjs/gumscriptscheduler.c), [interceptor](https://github.com/frida/frida-gum/blob/02bc3d805455f79eef1de1acbbfe72328d477756/gum/guminterceptor.c), [QuickJS](https://github.com/frida/frida-gum/blob/02bc3d805455f79eef1de1acbbfe72328d477756/bindings/gumjs/gumquickscript.c), [V8](https://github.com/frida/frida-gum/blob/02bc3d805455f79eef1de1acbbfe72328d477756/bindings/gumjs/gumv8script.cpp) | `02bc3d80` | GObject type registrations retain runtime identifiers independently of agent filename/export names. The scheduler also names its thread `gum-js-loop`. |
| [Frida cloaking](https://github.com/frida/frida-core/blob/b66a485f7c8f12407c51d5994907eca549d27066/lib/payload/cloak.vala) | `b66a485f` | `ThreadListCloaker` interposes libc directory operations. Detection enumerates kernel directory records with `getdents64` instead. |
| [ultrafunkamsterdam repository](https://github.com/ultrafunkamsterdam/undetected-frida/tree/9dbab1d54bcd2a2c48862f659bada679808a2b1f), [actual patch repository](https://github.com/ultrafunkamsterdam/undetected-frida-patches/tree/136b8ae1e437a5ede1135ba08c4ad8236995ed51) | `9dbab1d5`, `136b8ae1` | The release workflow obtains patches from a separate repository. Patches change agent names/exports, RPC/protocol strings and thread names. These changes make simple name blacklists insufficient. |
| [zer0def patches](https://github.com/zer0def/undetected-frida/tree/7039ffb6ea1be3ddb63449bcd4d59d384fb1ec7e), [release workflow](https://github.com/zer0def/undetected-frida/blob/7039ffb6ea1be3ddb63449bcd4d59d384fb1ec7e/.github/workflows/build.yml) | `7039ffb6` | The active workflow applies strongR, Florida and rycoh99 patches. It randomizes selected names, changes memfd names to `jit-cache`, and includes a patch reversing selected rodata strings. Optional patchsets in the repository were not assumed to be active. |

The memory check requires `GumInterceptor`, a scheduler type, and either the
QuickJS or V8 script type in **one loaded ELF image**. It also recognizes the
reversed scheduler prefix from the inspected strongR patch. Only hashes of these
complete type names enter the detector's native constant data, avoiding a match
against its own image. This is a composite signature, not cryptographic evidence
that every matching program must be Frida.

## Runtime behavior

- Check before JNI decoder registration, then at most once per second when decoding.
- Abort immediately on a specific agent/Gadget executable mapping, a complete Gum
  image signature, or one of the specific Frida thread names. Generic `gmain`,
  `gdbus`, and `jit-cache` names are insufficient.
- Inspect live ELF segments, including renamed libraries, instead of scanning all
  device files or probing a server port. Unchanged clean images are cached; new
  images are examined at the next scheduled check. Read-only data is preferred;
  older ELF layouts combining code and rodata are supported.
- Read this process's memory with `process_vm_readv`. `/proc/self/mem` was rejected
  by the ordinary Android app sandbox in the first compatibility test and is not
  required by the final implementation. Inspection failures abort with a distinct
  diagnostic rather than being reported as a positive Frida match.
- O-MVLL builds additionally flatten the periodic guard entry; compilation checks
  that the requested pass was actually applied.

There is no idle watchdog. A late-loaded runtime is checked when decoding resumes
after the interval. This does not continuously authenticate already scanned image
bytes or make arbitrary in-process tampering impossible.

## Observed results

| Test | Result |
| --- | --- |
| Native host regressions | 9 passed: clean process/self-image, generic names, specific thread, agent mapping, renamed QuickJS/V8 images, reversed scheduler, separated identifiers, and late loading. |
| Complete Python tooling suite | 19 passed. |
| Protected ARM64 fixture on API 37, 16 KiB AVD with ARM translation | All 10 release instrumentation tests passed without Frida. |
| Upstream Gadget 17.19.0, native x86_64 Android harness | Control runs normally; immediate and periodic checks both abort with `Frida Gum runtime detected`. |
| zer0def Gadget 17.19.0, native x86_64 Android harness | Control runs normally; immediate and periodic checks both abort with the same Gum-image diagnostic. |
| ultrafunkamsterdam Gadget 17.7.2 | Control fails to load because its 4 KiB ELF alignment is incompatible with this 16 KiB system. No detection success is claimed. The latest repository release had no Gadget asset; 17.7.2 was the newest available asset inspected. |
| Protected WeKit 0.13.1 integration | O-MVLL release build passed; standalone AVD cold start completed in 1,838 ms and the app stayed alive. This is not a measurement of detector overhead in isolation or injected WeChat compatibility. |

Machine-readable reports, including input binary hashes and abort messages, are in
[frida/](frida/). Gadgets were copied under the neutral filename `libfixture.so`;
the successful checks used the composite Gum-image signature. No supplied binary,
guard instruction or signature was patched. Controls omit detector calls and must
reach the ready/alive markers before a negative case can establish useful evidence.

The native harness compiles the exact production detector source for the AVD's
native ISA, loads a real Gadget with a minimal interval script, and invokes the
same check used by the decoder. It does not hook or inject into another app. It
does not exercise the surrounding APK-signature guard in that shell process;
that is covered separately by the protected ARM64 app tests. Early ARM64 Gadget
attempts under translation failed before detector entry and were discarded as
inconclusive. These results do not establish real ARM64 device Frida compatibility.

The published Gadget binaries still contain the original Gum identifiers. Several
fork patches target agent post-processing specifically. Actual **server-injected
fork agents** were not executed in these tests; source inspection plus the reversed
identifier regression is not a substitute for that runtime validation.

A separate 4 KiB AVD image download was attempted for the older Gadget but failed
with a peer-disconnected SDK download error. That does not change its unverified
runtime status. The final fixture also passed export/alignment/sentinel auditing;
the existing APK-only unidbg replay still terminated in native abort with zero
recovered strings.

## Reproduce

Download a matching official/repository Gadget release for the selected emulator's
native ABI and record its SHA-256. Binaries remain local build inputs, not repository
files. Use `control`, `before`, and `late` sequentially on one emulator:

```sh
python3 tools/test_frida_guard.py
python3 tools/test-frida-android.py \
  --gadget build/frida/stock-x86_64.so \
  --ndk "$ANDROID_HOME/ndk/29.0.14206865" \
  --serial emulator-5580 --mode control --output build/frida/native-stock-control
# Repeat with --mode before and --mode late and distinct output directories.
```

The runner accepts emulator serials only and distinguishes native guard aborts
from loader errors, unrelated crashes and failed controls. It writes its isolated
probe under `/data/local/tmp/lsp-frida-owned-probe` on that emulator.

## Limits

Custom Frida builds can change the retained type registrations, remove ELF headers,
alter inspection results or patch the detector. ELF identity caching does not catch
all modifications at an already scanned address. An authorized host can still expose
plaintext. There is no claim of universal Frida detection, defeat of arbitrary root
control, or measured compatibility with every ROM. Real WeChat Xposed/Zygisk injection
and API 28 execution remain separate unverified integration paths.
