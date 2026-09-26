# ARM64 decoder execution without an Android device

From the repository root, run:

```sh
python3 tools/test-native-arm64.py \
  --ndk "$ANDROID_HOME/ndk/29.0.14206865" \
  --omvll-plugin "$HOME/.local/share/lspeciallyparanoid/toolchains/omvll-1.9.1/omvll-ndk.so" \
  --omvll-python "$HOME/.local/share/lspeciallyparanoid/toolchains/omvll-1.9.1/Python-3.10.7/Lib"
```

Requires Linux, the project build dependencies, JDK 17 or later with `javac`,
`qemu-aarch64`, and the pinned native toolchain. `tools/setup-native-toolchain.py`
installs the native toolchain. No emulator, device, or APK installation is used.

The Java fixture generator uses the production `NativeStringRegistry` and JDK
encryption. It writes the original raw UTF-16 inputs separately as an oracle,
then emits production decoder sources. The C harness includes the actual decoder
and provides a small JNI function table for string construction and exceptions.
The test executes both ordinary and O-MVLL builds as static bionic ARM64 programs
under QEMU, comparing every returned code unit against the original input.

Coverage includes 62 successful strings across literal/resource domains, empty
and long strings, NUL, lone surrogates, supplementary characters, repeated values,
and deterministic random UTF-16. A damaged record must fail authentication;
unknown IDs, an existing exception, and `NewString` allocation failure are also
checked. Hardened builds must produce both selection markers and logs confirming
that the intended passes applied changes.

Outputs and compiler/pass/execution logs remain under
`build/native-arm64-smoke/`, with a machine-readable `report.json`.

This verifies execution of real ARM64 decoder instructions and compiler
transformations. It **does not validate ART/JNI registration, Android library
loading, APK installation, page-size behavior, Xposed, or Zygisk**. Existing host
JNI tests and Android instrumentation cover different parts of that boundary.
