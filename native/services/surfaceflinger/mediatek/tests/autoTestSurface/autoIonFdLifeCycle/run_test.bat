adb -s %1 remount
rem adb -s %1 push ..\..\..\..\..\..\..\..\out\target\product\mt6595_phone_v1\system\bin\autoIonFdLifeCycleUnitTest /system/bin
adb -s %1 push ..\bin\autoIonFdLifeCycleUnitTest /system/bin
adb -s %1 shell chmod 777 /system/bin/autoIonFdLifeCycleUnitTest
adb -s %1 shell autoIonFdLifeCycleUnitTest 2 1000 0 15000
