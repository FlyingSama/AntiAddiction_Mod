package com.antiaddiction.storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class EncryptedLevelDatFormat {

    public static final byte[] MAGIC   = "AALOCK01".getBytes(StandardCharsets.US_ASCII);
    public static final byte   VERSION = 0x01;
    public static final int    NONCE_LEN     = 12;
    public static final int    GCM_TAG_BYTES = 16;

    public record Header(byte version, byte[] nonce, String kid, String aad, int dataOffset) {}

    private EncryptedLevelDatFormat() {}

    public static boolean isEncrypted(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            byte[] buf = new byte[8];
            return is.read(buf) == 8 && Arrays.equals(buf, MAGIC);
        } catch (Exception e) {
            return false;
        }
    }

    public static Header readHeader(Path path) throws IOException {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            byte[] magic = dis.readNBytes(8);
            if (!Arrays.equals(magic, MAGIC)) throw new IOException("not an encrypted level.dat (bad magic)");
            byte version  = dis.readByte();
            byte[] nonce  = dis.readNBytes(NONCE_LEN);
            int kidLen    = dis.readInt();
            byte[] kidB   = dis.readNBytes(kidLen);
            int aadLen    = dis.readInt();
            byte[] aadB   = dis.readNBytes(aadLen);
            int dataOffset = 8 + 1 + NONCE_LEN + 4 + kidLen + 4 + aadLen;
            return new Header(version, nonce,
                    new String(kidB, StandardCharsets.US_ASCII),
                    new String(aadB, StandardCharsets.UTF_8),
                    dataOffset);
        }
    }

    public static void writeHeader(OutputStream os, byte[] nonce, String kid, String aad) throws IOException {
        byte[] kidB = kid.getBytes(StandardCharsets.US_ASCII);
        byte[] aadB = aad.getBytes(StandardCharsets.UTF_8);
        DataOutputStream dos = new DataOutputStream(os);
        os.write(MAGIC);
        dos.writeByte(VERSION);
        os.write(nonce);
        dos.writeInt(kidB.length);
        os.write(kidB);
        dos.writeInt(aadB.length);
        os.write(aadB);
        dos.flush();
    }
}
