package android.hardware.fingerprint; 

interface IFpsFingerClient
{  
    void getValue(int type, int score);
}
