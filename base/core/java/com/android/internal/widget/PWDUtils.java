
package com.android.internal.widget;

import android.text.TextUtils;
import android.util.Base64;

/**
 * @hide
 */
public class PWDUtils {
    public final static String KEY_PWD = "key_lock_pwd";

    /**
     * @hide
     */
    public static String encrypt(String toEncrypt) {
        String encrypted = toEncrypt;
        try {
            encrypted = Base64.encodeToString(toEncrypt.getBytes(), Base64.DEFAULT);
        } catch (Exception e) {
        }
        if (TextUtils.isEmpty(encrypted)) {
            encrypted = toEncrypt;
        }
        return encrypted;
    }

    /**
     * @hide
     */
    public static String decrypt(String encrypted) {
        String decrypted = encrypted;
        try {
            decrypted = new String(Base64.decode(encrypted.getBytes(), Base64.DEFAULT));
        } catch (Exception e) {
            return null;
        }
        return decrypted;
    }

}
