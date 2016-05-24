adb -s %1 remount
rem adb -s %1 push ..\..\..\..\..\..\..\..\out\target\product\mt6595_phone_v1\system\bin\autoVideoTelephonyUnitTest /system/bin
adb -s %1 push ..\bin\autoVideoTelephonyUnitTest /system/bin
adb -s %1 shell chmod 777 /system/bin/autoVideoTelephonyUnitTest
adb -s %1 shell autoVideoTelephonyUnitTest 1 2 60
