package android.hardware.fingerprint;
import android.hardware.fingerprint.IFpsFingerClient;

interface IFpsFingerManager  
{  
    int SetKeyCode(int keycode);
    void waitScreenOn();
    void listen(IFpsFingerClient client);
    int mmiFpTest();
    int FpEnFun(int enable);
    int FpIsFun();
    int FpSetFun(int enable);
    int FpGetFun();
    int FpSetEnFun();
    int FpBroadcast();
    int FpMessage();
    int FpIsFunBroad();
    int FpNull();
}
