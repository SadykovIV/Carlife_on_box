package org.carlife.hulab;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
/** Decode generated, neutral-image test fixtures stored as reviewable text. */
final class HexFixture {
    static byte[] read(Path originalName) throws IOException {
        String hex = new String(Files.readAllBytes(Paths.get(originalName.toString() + ".hex")), StandardCharsets.US_ASCII).replaceAll("\\s+", "");
        if ((hex.length() & 1) != 0) throw new IOException("Odd fixture hex length");
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(hex.charAt(2 * i), 16), low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) throw new IOException("Invalid fixture hex");
            result[i] = (byte)((high << 4) | low);
        }
        return result;
    }
}
