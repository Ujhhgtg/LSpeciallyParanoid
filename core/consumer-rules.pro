# The JVM decoder and helpers use direct bytecode references. R8 may shrink,
# optimize and rename them normally; no reflection-based keep rules are needed.
# Native backends emit their own exact rules for JNI registration separately.
