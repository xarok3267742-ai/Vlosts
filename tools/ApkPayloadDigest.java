import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ApkPayloadDigest {
    private static final byte[] DOMAIN = "v-slot-apk-payload-v2\n".getBytes(StandardCharsets.US_ASCII);

    private record EntryDigest(String name, byte[] digest) {}

    private ApkPayloadDigest() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            System.err.println("Usage: ApkPayloadDigest.java APK");
            System.exit(64);
        }
        System.out.println(payloadDigest(Path.of(arguments[0])));
    }

    static String payloadDigest(Path apk) throws Exception {
        List<EntryDigest> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (entry.isDirectory() || isNonRuntimeMetadata(entry.getName())) {
                    continue;
                }
                MessageDigest entryDigest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        entryDigest.update(buffer, 0, count);
                    }
                }
                entries.add(new EntryDigest(entry.getName(), entryDigest.digest()));
            }
        }
        entries.sort(
            Comparator.comparing(EntryDigest::name)
                .thenComparing(entry -> HexFormat.of().formatHex(entry.digest()))
        );

        MessageDigest payloadDigest = MessageDigest.getInstance("SHA-256");
        payloadDigest.update(DOMAIN);
        for (EntryDigest entry : entries) {
            byte[] name = entry.name().getBytes(StandardCharsets.UTF_8);
            payloadDigest.update(ByteBuffer.allocate(Integer.BYTES).putInt(name.length).array());
            payloadDigest.update(name);
            payloadDigest.update(HexFormat.of().formatHex(entry.digest()).getBytes(StandardCharsets.US_ASCII));
        }
        return HexFormat.of().formatHex(payloadDigest.digest()).toLowerCase(Locale.ROOT);
    }

    private static boolean isNonRuntimeMetadata(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        String leaf = upper.substring("META-INF/".length());
        return leaf.equals("MANIFEST.MF") || leaf.equals("VERSION-CONTROL-INFO.TEXTPROTO") ||
            leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA") ||
            leaf.endsWith(".EC");
    }
}
