adb -s %1 remount
rem adb -s %1 push ..\..\..\..\..\..\..\..\out\target\product\mt6595_phone_v1\system\bin\autoVDSvlwUnitTest /system/bin
adb -s %1 push ..\bin\autoVDSvlwUnitTest /system/bin
adb -s %1 shell chmod 777 /system/bin/autoVDSvlwUnitTest
adb -s %1 shell autoVDSvlwUnitTest 1 1 120 0 0
