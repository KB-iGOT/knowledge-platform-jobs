package org.sunbird.job.certpublic.functions;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Key;

public class EncryptionService {

    private String encryption_key ="";
    private EncryptionService(String encryption_key){
        this.encryption_key = encryption_key;
    }
    // Private static instance of the singleton
    private static EncryptionService instance;

    // Public static method to get the singleton instance
    public static EncryptionService getInstance(String encryption_key) {
        if (instance == null) {
            instance = new EncryptionService(encryption_key);
        }
        return instance;
    }

    private static Cipher c;
    static String ALGORITHM = "AES";
    int ITERATIONS = 3;
    static byte[] keyValue =
            new byte[]{'T', 'h', 'i', 's', 'A', 's', 'I', 'S', 'e', 'r', 'c', 'e', 'K', 't', 'e', 'y'};

    static {
        try {
            Key key = generateKey();
            c = Cipher.getInstance(ALGORITHM);
            c.init(Cipher.ENCRYPT_MODE, key);
        } catch (Exception e) {
        }
    }

    private static Key generateKey() {
        return new SecretKeySpec(keyValue, ALGORITHM);
    }


    public String encryptData(String value) {
        String valueToEnc = null;
        String encryption_key = "password";
        String eValue = value;
        for (int i = 0; i < ITERATIONS; i++) {
            valueToEnc = encryption_key + eValue;
            byte[] encValue = new byte[0];
            try {
                encValue = c.doFinal(valueToEnc.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
            }
            eValue = new BASE64Encoder().encode(encValue);
        }
        return eValue;
    }
}