package com.antiaddiction.storage;

import com.antiaddiction.AntiAddictionMod;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Arrays;

public final class LevelDatCipher {

    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private LevelDatCipher() {}

    public static boolean isEncrypted(Path path) {
        return EncryptedLevelDatFormat.isEncrypted(path);
    }

    public static void encryptInPlace(Path levelDat, byte[] key, String kid, String saveName) throws Exception {
        if (isEncrypted(levelDat)) return;

        Path parent  = levelDat.getParent();
        Path backup  = parent.resolve("level.dat.aa-bak");
        Path tmpEnc  = parent.resolve("level.dat.aa-tmp-enc");

        byte[] plaintext = Files.readAllBytes(levelDat);
        byte[] nonce     = new byte[EncryptedLevelDatFormat.NONCE_LEN];
        RANDOM.nextBytes(nonce);

        String aad      = "aa-leveldat|" + saveName + "|" + kid;
        byte[] aadBytes = aad.getBytes(StandardCharsets.UTF_8);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aadBytes);
        byte[] ciphertext = cipher.doFinal(plaintext);

        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmpEnc,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
            EncryptedLevelDatFormat.writeHeader(os, nonce, kid, aad);
            os.write(ciphertext);
        }

        Files.copy(levelDat, backup, StandardCopyOption.REPLACE_EXISTING);

        try {
            Files.move(tmpEnc, levelDat, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmpEnc, levelDat, StandardCopyOption.REPLACE_EXISTING);
        }

        try { Files.deleteIfExists(backup); } catch (Exception ignored) {}

        Path readme = parent.resolve("README-encrypted.txt");
        if (!Files.exists(readme)) {
            try {
                Files.writeString(readme,
                        "此存档由防沉迷 Mod 加密保护。\n" +
                        "删除或禁用 Mod 后，此存档将无法在原版 Minecraft 中打开。\n" +
                        "请通过防沉迷 Mod 完成实名认证后正常进入游戏以访问此存档。\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (Exception ignored) {}
        }
    }

    public static Path decryptToTemp(Path levelDat, byte[] key) throws Exception {
        EncryptedLevelDatFormat.Header header = EncryptedLevelDatFormat.readHeader(levelDat);

        byte[] fileBytes  = Files.readAllBytes(levelDat);
        int    dataOffset = header.dataOffset();
        byte[] ciphertext = Arrays.copyOfRange(fileBytes, dataOffset, fileBytes.length);
        byte[] aadBytes   = header.aad().getBytes(StandardCharsets.UTF_8);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, header.nonce()));
        cipher.updateAAD(aadBytes);
        byte[] plaintext = cipher.doFinal(ciphertext);

        Path tmp = levelDat.getParent().resolve("level.dat.aa-tmp-dec");
        Files.write(tmp, plaintext,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        return tmp;
    }

    public static void cleanupTempFiles(Path saveDir) {
        try (var stream = Files.list(saveDir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".aa-tmp-dec") || n.endsWith(".aa-tmp-enc") || n.endsWith(".aa-bak");
            }).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    public static void cleanupAllSaveTempFiles(Path savesRoot) {
        try (var dirs = Files.list(savesRoot)) {
            dirs.filter(Files::isDirectory).forEach(LevelDatCipher::cleanupTempFiles);
        } catch (Exception ignored) {}
    }
}
