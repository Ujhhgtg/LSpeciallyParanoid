import dev.ujhhgtg.lsparanoid.processor.nativebackend.*;
import java.io.*;
import java.util.*;

/** Independent input strings are saved before their JDK-encrypted records are emitted. */
public final class GenerateFixture {
    private record Expected(long id, String text, boolean damaged) {}

    public static void main(String[] args) throws Exception {
        File output = new File(args[0]);
        output.mkdirs();
        byte[] entropy = new byte[32];
        for (int i = 0; i < entropy.length; ++i) entropy[i] = (byte)i;
        NativeBuildSpec spec = NativeBuildSpec.create(entropy, "arm64-execution-smoke:release");
        NativeStringRegistry literals = new NativeStringRegistry(spec);
        NativeStringRegistry resources = new NativeStringRegistry(spec, RecordDomain.RESOURCE);
        List<Expected> expected = new ArrayList<>();
        for (String text : List.of("", "plain", "\u0000middle\u0000", "\ud800", "\udfff", "\ud83d\ude80",
                "中文العربية", "duplicate", "duplicate", "long\u0000\ud800".repeat(16_384))) {
            expected.add(new Expected(literals.registerString(text), text, false));
        }
        Random random = new Random(0x4c535031L);
        for (int i = 0; i < 48; ++i) {
            char[] chars = new char[i * 11 + 1];
            for (int j = 0; j < chars.length; ++j) chars[j] = (char)random.nextInt(65_536);
            String text = new String(chars);
            expected.add(new Expected(literals.registerString(text), text, false));
        }
        for (String text : List.of("", "localized %1$s / %2$d", "\ud800\u0000resource", "duplicate")) {
            expected.add(new Expected(resources.registerString(text), text, false));
        }
        String damaged = "authentication failure fixture";
        long damagedId = literals.registerString(damaged);
        expected.add(new Expected(damagedId, damaged, true));
        List<NativeStringRecord> records = new ArrayList<>(literals.records());
        records.addAll(resources.records());
        for (int i = 0; i < records.size(); ++i) {
            NativeStringRecord record = records.get(i);
            if (record.getId() == damagedId) {
                byte[] ciphertext = record.getCiphertext();
                ciphertext[0] ^= 1;
                records.set(i, new NativeStringRecord(record.getId(), record.getDomain(), record.getUtf16Length(), record.getNonce(), ciphertext));
            }
        }
        long unknownId = -1;
        Set<Long> ids = new HashSet<>();
        for (NativeStringRecord record : records) ids.add(record.getId());
        while (ids.contains(unknownId)) --unknownId;
        NativeGenerator.generate(spec, records, output);
        try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(output, "expected.bin"))))) {
            data.writeInt(0x4c535454);
            data.writeInt(expected.size());
            data.writeLong(unknownId);
            for (Expected value : expected) {
                data.writeLong(value.id());
                data.writeBoolean(value.damaged());
                data.writeInt(value.text().length());
                for (int i = 0; i < value.text().length(); ++i) data.writeChar(value.text().charAt(i));
            }
        }
        System.out.println("Generated " + expected.size() + " independent UTF-16 fixtures and encrypted records");
    }
}
