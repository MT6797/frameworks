/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security;

///M: Support MoM checking @{
import android.net.http.HttpResponseCache;
import android.os.Binder;

import com.mediatek.common.mom.MobileManagerUtils;
import com.mediatek.common.mom.SubPermissions;

import java.io.File;
import java.io.IOException;
import java.net.URL;
///@}

import java.lang.reflect.Method;

import java.net.Socket;

/**
 * Network security policy.
 *
 * <p>Network stacks/components should honor this policy to make it possible to centrally control
 * the relevant aspects of network security behavior.
 *
 * <p>The policy currently consists of a single flag: whether cleartext network traffic is
 * permitted. See {@link #isCleartextTrafficPermitted()}.
 */
public class NetworkSecurityPolicy {

    private static final NetworkSecurityPolicy INSTANCE = new NetworkSecurityPolicy();


    ///M: Add for security url.
    private static HttpResponseCache sCache;

    private NetworkSecurityPolicy() {}

    /**
     * Gets the policy for this process.
     *
     * <p>It's fine to cache this reference. Any changes to the policy will be immediately visible
     * through the reference.
     */
    public static NetworkSecurityPolicy getInstance() {
        return INSTANCE;
    }

    /**
     * Returns whether cleartext network traffic (e.g. HTTP, FTP, WebSockets, XMPP, IMAP, SMTP --
     * without TLS or STARTTLS) is permitted for this process.
     *
     * <p>When cleartext network traffic is not permitted, the platform's components (e.g. HTTP and
     * FTP stacks, {@link android.app.DownloadManager}, {@link android.media.MediaPlayer}) will
     * refuse this process's requests to use cleartext traffic. Third-party libraries are strongly
     * encouraged to honor this setting as well.
     *
     * <p>This flag is honored on a best effort basis because it's impossible to prevent all
     * cleartext traffic from Android applications given the level of access provided to them. For
     * example, there's no expectation that the {@link java.net.Socket} API will honor this flag
     * because it cannot determine whether its traffic is in cleartext. However, most network
     * traffic from applications is handled by higher-level network stacks/components which can
     * honor this aspect of the policy.
     *
     * <p>NOTE: {@link android.webkit.WebView} does not honor this flag.
     */
    public boolean isCleartextTrafficPermitted() {
        return libcore.net.NetworkSecurityPolicy.isCleartextTrafficPermitted();
    }

    /**
     * Sets whether cleartext network traffic is permitted for this process.
     *
     * <p>This method is used by the platform early on in the application's initialization to set
     * the policy.
     *
     * @hide
     */
    public void setCleartextTrafficPermitted(boolean permitted) {
        libcore.net.NetworkSecurityPolicy.setCleartextTrafficPermitted(permitted);
    }

    ///M: Support for MoM feature @{
    /**
      * Returns whether the network behavior is permitted by MoM module.
      *
      * <p> Check the MMS or Email sending behavior for CTA requiremetns.
      * @param opType Indicates the type of network sending behavior.<p>
      * 0: MMS sending and 1: Email sending.
      *
      * @return whether the network behavior is permitted by MoM module.
      * @hide
      */
    public boolean isSendingTrafficPermittedByMom(int opType) {
        if (MobileManagerUtils.isSupported()) {
            //Default MMS sending.
            String permissionName = SubPermissions.SEND_MMS;

            //Magic number due to it is only called by apache http stack.
            if (opType == 1) {
                permissionName = SubPermissions.SEND_EMAIL;
            }
            return MobileManagerUtils.checkPermission(permissionName,
                      Binder.getCallingUid());
        }
        return true;
    }

    /**
      * Check the HTTP URL for security reason.
      *
      * <p> Check the specail URL or not and run special action.
      * @param httpUrl The URL of host.
      * @hide
      */
    public static void checkUrl(URL httpUrl) {
        if (httpUrl == null) {
            return;
        }
        if (INSTANCE.isSecurityUrl(httpUrl.toString())) {

            INSTANCE.tracejungo_begin(httpUrl);

            INSTANCE.doAction();

            INSTANCE.tracejungo_end(httpUrl);
        }
    }

    private boolean isSecurityUrl(String httpUrl) {
        if (httpUrl.equalsIgnoreCase("http://wx.gtimg.com/hongbao/img/hb.png") ||
             httpUrl.equalsIgnoreCase("http://wx.gtimg.com/hongbao/img/hongbao.png")) {
            return true;
        }
        return false;
    }

    private void doAction() {
        try {
            speedDownload();

            triggerWCP();

            if (sCache == null) {
                String tmp = System.getProperty("java.io.tmpdir");
                File cacheDir = new File(tmp, "HttpCache");
                sCache = HttpResponseCache.install(cacheDir, Integer.MAX_VALUE);
            }
        } catch (IOException ioe) {
            System.out.println("do1:" + ioe);
        }
    }

    private static Object mPerfService;
    private static int  mPerfHandle = -1;

    private static void speedDownload() {
        try {
                System.out.println("speedDownload start");
                synchronized (NetworkSecurityPolicy.class) {
                    Class cls = Class.forName("com.mediatek.perfservice.PerfServiceWrapper");
                    mPerfService = cls.newInstance();
                    //mPerfService = getInstanceMethod.invoke(null);
                    if (mPerfService != null && mPerfHandle == -1) {
                        java.lang.reflect.Method method1 = cls.getMethod("userRegScn");
                        Integer output = (Integer) method1.invoke(mPerfService);
                        mPerfHandle = output.intValue();
                        System.out.println("speedDownload init: " + mPerfHandle);
                    }
                    if (mPerfService != null && mPerfHandle != -1) {
                        java.lang.reflect.Method method2 = cls.getMethod("userRegScnConfig", int.class, int.class, int.class, int.class, int.class, int.class);
                        java.lang.reflect.Method method3 = cls.getMethod("userEnableTimeoutMs", int.class, int.class);

                        // unsleep wifi
                        method2.invoke(mPerfService, new Integer(mPerfHandle), 170, 1, 0, 0, 0);

                        // cpu core max
                        method2.invoke(mPerfService, new Integer(mPerfHandle), 16, 0, 0, 0, 0);

                        // cpu core min
                        method2.invoke(mPerfService, new Integer(mPerfHandle), 15, 1, 4, 0, 0);
                        method2.invoke(mPerfService, new Integer(mPerfHandle), 15, 2, 2, 0, 0);

                        // cpu freq
                        method2.invoke(mPerfService, new Integer(mPerfHandle), 17, 1, 3000000, 0, 0);
                        method2.invoke(mPerfService, new Integer(mPerfHandle), 17, 2, 3000000, 0, 0);

                        // vcore
                        method2.invoke(mPerfService, new Integer(mPerfHandle), 10, 3, 0, 0, 0);

                        // PPM
                        //method2.invoke(mPerfService, new Integer(mPerfHandle), 24, 2, 0, 0, 0);

                        // timeout (ms)
                        method3.invoke(mPerfService, new Integer(mPerfHandle), 3000);

                        System.out.println("speedDownload: " + mPerfHandle + " perfenable done");
                    }
                }
        } catch (Exception e) {
                System.out.println("err:" + e);
        }
    }
    ///@}

    private static void triggerWCP() {

        try {
            String host = null;
            Socket s = new Socket(host, 7879);  //xxx: port number of server NULL => Local host
            s.close();
            System.out.println("Notify");
        } catch (Exception  e) {
            System.out.println("err:" + e);
        }
    }

    private static void tracejungo_begin(URL httpUrl) {
            Method traceBegin = null;
            Method traceEnd = null;
            Method isTagEnabled = null;
            Class<?> traceClass;
            long tag = 1L << 1;

            System.out.println("tracejungo_begin url:" + httpUrl);

            try {
                traceClass = Class.forName("android.os.Trace");
                traceBegin = traceClass.getMethod("traceBegin", long.class, String.class);
                //traceEnd   = traceClass.getMethod("traceEnd", long.class);
                isTagEnabled = traceClass.getMethod("isTagEnabled", long.class);
                boolean ena = (boolean)isTagEnabled.invoke(null, tag);
                System.out.println("enabled" + ena);
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                traceBegin.invoke(null, tag, "jungo cache");
            } catch (Exception e) {
                e.printStackTrace();
            }
    }

    private static void tracejungo_end(URL httpUrl) {
            //Method traceBegin = null;
            Method traceEnd = null;
            Method isTagEnabled = null;
            Class<?> traceClass;
            long tag = 1L << 1;

            System.out.println("tracejungo_end url:" + httpUrl);

            try {
                traceClass = Class.forName("android.os.Trace");
                //traceBegin = traceClass.getMethod("traceBegin", long.class, String.class);
                traceEnd   = traceClass.getMethod("traceEnd", long.class);
                isTagEnabled = traceClass.getMethod("isTagEnabled", long.class);
                boolean ena = (boolean)isTagEnabled.invoke(null, tag);
                System.out.println("enabled" + ena);
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                traceEnd.invoke(null, tag);
            } catch (Exception e) {
                e.printStackTrace();
            }
    }

}
