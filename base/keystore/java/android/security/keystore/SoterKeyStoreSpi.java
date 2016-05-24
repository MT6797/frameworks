package android.security.keystore;

import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.KeyStoreException;

import android.security.Credentials;
import android.security.KeyStore;
import android.security.keystore.AndroidKeyStoreProvider;
import android.text.TextUtils;
import android.util.Log;

/**
 * @hide
 */
public class SoterKeyStoreSpi extends android.security.keystore.AndroidKeyStoreSpi{

    private KeyStore mKeyStore = null;

    public SoterKeyStoreSpi() {
        mKeyStore = KeyStore.getInstance();
    }

    @Override
    public Key engineGetKey(String alias, char[] password) throws NoSuchAlgorithmException, UnrecoverableKeyException {
        if (isPrivateKeyEntry(alias)) {
            String privateKeyAlias = Credentials.USER_PRIVATE_KEY + alias;

            if (password != null && "from_soter_ui".equals(String.valueOf(password))) {
                Log.d(SoterUtil.TAG, "SoterKeyStoreSpi.engineGetKey: loadJsonPublicKeyFromKeystore");
                return SoterKeyStoreProvider.loadJsonPublicKeyFromKeystore(mKeyStore, privateKeyAlias);
            } else {
                Log.d(SoterUtil.TAG, "SoterKeyStoreSpi.engineGetKey: loadAndroidKeyStorePrivateKeyFromKeystore");
                return SoterKeyStoreProvider.loadAndroidKeyStorePrivateKeyFromKeystore(mKeyStore, privateKeyAlias);
            }
        } else if (isSecretKeyEntry(alias)) {
            String secretKeyAlias = Credentials.USER_SECRET_KEY + alias;
            return AndroidKeyStoreProvider.loadAndroidKeyStoreSecretKeyFromKeystore(mKeyStore, secretKeyAlias);
        } else {
            Log.w(SoterUtil.TAG, "SoterKeyStoreSpi.engineGetKey: key not found");
            return null;
        }
    }

    private boolean isPrivateKeyEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.USER_PRIVATE_KEY + alias);
    }

    private boolean isSecretKeyEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }
        return mKeyStore.contains(Credentials.USER_SECRET_KEY + alias);
    }

    @Override
    public void engineDeleteEntry(String alias) throws KeyStoreException {
        Log.d(SoterUtil.TAG, "SoterKeyStoreSpi.engineDeleteEntry: alias = " + alias);
        if (!engineContainsAlias(alias)) {
            return;
        }
        if (!(mKeyStore.delete(Credentials.USER_PRIVATE_KEY + alias)
                | mKeyStore.delete(Credentials.USER_CERTIFICATE + alias))) {
            throw new KeyStoreException("Failed to delete entry: " + alias);
        }
    }

    @Override
    public boolean engineContainsAlias(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.USER_PRIVATE_KEY + alias)
                || mKeyStore.contains(Credentials.USER_CERTIFICATE + alias);
    }
}
