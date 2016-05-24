adb -s %1 remount
rem adb -s %1 push ..\..\..\..\..\..\..\..\out\target\product\mt6595_phone_v1\system\bin\autoVDSaddVideoUnitTest /system/bin
adb -s %1 push ..\bin\autoVDSaddVideoUnitTest /system/bin
adb -s %1 shell chmod 777 /system/bin/autoVDSaddVideoUnitTest
adb -s %1 shell autoVDSaddVideoUnitTest 1 1 120 1 0
