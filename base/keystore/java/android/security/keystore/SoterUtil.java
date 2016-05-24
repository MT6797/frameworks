package android.security.keystore;

import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import android.util.Base64;

/**
 * @hide
 */
public class SoterUtil {
    public static final String TAG = "Soter";

    private static final boolean FEATURE_SUPPORTED =
            SystemProperties.get("ro.mtk_soter_support").equals("1");

    private static final String PARAM_NEED_AUTO_SIGNED_WITH_ATTK_WHEN_GET_PUBLIC_KEY       = "auto_signed_when_get_pubkey_attk";
    private static final String PARAM_NEED_AUTO_SIGNED_WITH_COMMON_KEY_WHEN_GET_PUBLIC_KEY = "auto_signed_when_get_pubkey";
    private static final String PARAM_NEED_AUTO_ADD_COUNTER_WHEN_GET_PUBLIC_KEY            = "addcounter";
    private static final String PARAM_NEED_AUTO_ADD_SECMSG_FID_COUNTER_WHEN_SIGN           = "secmsg_and_counter_signed_when_sign";
    private static final String PARAM_NEED_NEXT_ATTK                                       = "next_attk";

    private static final int RAW_LENGTH_PREFIX = 4;
    public static final String JSON_KEY_PUBLIC = "pub_key";

    public static boolean isSupported() {
        return FEATURE_SUPPORTED;
    }

    public static SoterRSAKeyGenParameterSpec convertKeyNameToParameterSpec(String name) {
        if (TextUtils.isEmpty(name)) {
            Log.e(TAG, "SoterUtil.convertKeyNameToParameterSpec: name is empty");
            return null;
        }
        String[] splits = name.split("\\.");
        if (splits == null || splits.length <= 1) {
            Log.w(TAG, "SoterUtil.convertKeyNameToParameterSpec: pure alias, no parameter");
            return null;
        }
        boolean isForSoter = true;
        boolean isAutoSignedWithAttkWhenGetPublicKey = false;
        boolean isAutoSignedWithCommonkWhenGetPublicKey = false;
        String mAutoSignedKeyNameWhenGetPublicKey = "";
        boolean isSecmsgFidCounterSignedWhenSign = false;
        boolean isAutoAddCounterWhenGetPublicKey = false;
        boolean isNeedNextAttk = false;

        if (contains(PARAM_NEED_AUTO_SIGNED_WITH_ATTK_WHEN_GET_PUBLIC_KEY, splits)) {
            isAutoSignedWithAttkWhenGetPublicKey = true;
        } else {
            String entireCommonKeyExpr = retrieveItemWithPrefix(PARAM_NEED_AUTO_SIGNED_WITH_COMMON_KEY_WHEN_GET_PUBLIC_KEY, splits);
            if (!TextUtils.isEmpty(entireCommonKeyExpr)) {
                String commonKeyName = retrieveKeyNameFromExpr(entireCommonKeyExpr);
                if(!TextUtils.isEmpty(commonKeyName)) {
                    isAutoSignedWithCommonkWhenGetPublicKey = true;
                    mAutoSignedKeyNameWhenGetPublicKey = commonKeyName;
                }
            }
        }

        if (contains(PARAM_NEED_AUTO_ADD_SECMSG_FID_COUNTER_WHEN_SIGN, splits)) {
            isSecmsgFidCounterSignedWhenSign = true;
        }
        if (contains(PARAM_NEED_AUTO_ADD_COUNTER_WHEN_GET_PUBLIC_KEY, splits)) {
            isAutoAddCounterWhenGetPublicKey = true;

            if(contains(PARAM_NEED_NEXT_ATTK, splits)) {
                isNeedNextAttk = true;
            }
        }
        SoterRSAKeyGenParameterSpec spec = new SoterRSAKeyGenParameterSpec(isForSoter,
                isAutoSignedWithAttkWhenGetPublicKey,isAutoSignedWithCommonkWhenGetPublicKey,
                mAutoSignedKeyNameWhenGetPublicKey, isSecmsgFidCounterSignedWhenSign,
                isAutoAddCounterWhenGetPublicKey, isNeedNextAttk);
        Log.i(TAG, "SoterUtil.convertKeyNameToParameterSpec: spec: " + spec.toString());
        return spec;
    }

    private static String retrieveKeyNameFromExpr(String expr) {
        if (!TextUtils.isEmpty(expr)) {
            int startPos = expr.indexOf("(");
            int endPos = expr.indexOf(")");
            if (startPos >= 0 && endPos > startPos) {
                return expr.substring(startPos + 1, endPos);
            }
            Log.e(TAG, "SoterUtil.retrieveKeyNameFromExpr: no key name");
            return null;
        } else {
            Log.e(TAG, "SoterUtil.retrieveKeyNameFromExpr: expr is null");
            return null;
        }
    }


    private static boolean contains(String target, String[] src) {
        if (src == null || src.length == 0 || TextUtils.isEmpty(target)) {
            Log.e(TAG, "SoterUtil.contains: param error, target = " + target + ", src = " + src);
            throw new IllegalArgumentException("param error");
        }
        for (String item : src) {
            if (target.equals(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     *
     * @param prefix the target prefix
     * @param src the source array
     * @return the entire str
     */
    private static String retrieveItemWithPrefix(String prefix, String[] src) {
        if (src == null || src.length == 0 || TextUtils.isEmpty(prefix)) {
            Log.e(TAG, "SoterUtil.retrieveItemWithPrefix: param error, prefix = " + prefix + ", src = " + src);
            throw new IllegalArgumentException("param error");
        }
        for (String item : src) {
            if (!TextUtils.isEmpty(item) && item.startsWith(prefix)) {
                return item;
            }
        }
        return null;
    }

    public static String getPureKeyAliasFromKeyName(String name) {
        Log.i(TAG, "SoterUtil.getPureKeyAliasFromKeyName: name = " + name);
        if (TextUtils.isEmpty(name)) {
            Log.e(TAG, "SoterUtil.getPureKeyAliasFromKeyName: name is null");
            return null;
        }
        String[] splits = name.split("\\.");
        if (splits == null || splits.length <= 1) {
            Log.d(TAG, "SoterUtil.getPureKeyAliasFromKeyName: pure alias");
            return name;
        }
        return splits[0];
    }

    public static byte[] getDataFromRaw(byte[] origin, String jsonKey) throws JSONException {
        if (TextUtils.isEmpty(jsonKey)) {
            Log.e(TAG, "SoterUtil.getDataFromRaw: jsonKey is null");
            return null;
        }
        if (origin == null) {
            Log.e(TAG, "SoterUtil.getDataFromRaw: json origin is null");
            return null;
        }
        JSONObject jsonObj = retriveJsonFromExportedData(origin);
        if (jsonObj != null && jsonObj.has(jsonKey)) {

            String base64pubkey = jsonObj.getString(jsonKey);
            Log.d(TAG, "SoterUtil.getDataFromRaw: base64 encoded public key: " + base64pubkey);

            // filter head and tail and lf
            String pureBase64pubkey = base64pubkey.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "").replace("\\n", "");
            Log.d(TAG, "SoterUtil.getDataFromRaw: pure base64 encoded public key: " + pureBase64pubkey);

            byte[] decoded = Base64.decode(pureBase64pubkey, Base64.DEFAULT);
            return decoded;
        }
        return null;
    }

    private static JSONObject retriveJsonFromExportedData(byte[] origin) {
        if (origin == null) {
            Log.e(TAG, "SoterUtil.retriveJsonFromExportedData: raw data is null");
            return null;
        }
        if (origin.length < RAW_LENGTH_PREFIX) {
            Log.e(TAG, "SoterUtil.retriveJsonFromExportedData: raw data length smaller than 4");
        }

        byte[] lengthBytes = new byte[4];
        System.arraycopy(origin, 0, lengthBytes, 0, 4);
        int rawLength = toInt(lengthBytes);
        Log.d(TAG, "SoterUtil.retriveJsonFromExportedData: parsed raw length: " + rawLength +
                ", origin.length = " + origin.length);

        byte[] rawJsonBytes = new byte[rawLength];
        if (origin.length <= RAW_LENGTH_PREFIX + rawLength) {
            Log.e(TAG, "SoterUtil.retriveJsonFromExportedData: length not correct");
            return null;
        }
        System.arraycopy(origin, RAW_LENGTH_PREFIX, rawJsonBytes, 0, rawLength);
        String jsonStr = new String(rawJsonBytes);
        Log.d(TAG, "SoterUtil.retriveJsonFromExportedData: converted json: " + jsonStr);
        try {
            return new JSONObject(jsonStr);
        } catch (JSONException e) {
            Log.e(TAG, "SoterUtil.retriveJsonFromExportedData: convert to json fail", e);
        }
        return null;
    }

    public static int toInt(byte[] bRefArr) {
        int iOutcome = 0;
        byte bLoop;

        for (int i = 0; i < bRefArr.length; i++) {
            bLoop = bRefArr[i];
            iOutcome += (bLoop & 0xFF) << (8 * i);
        }
        return iOutcome;
    }

}
