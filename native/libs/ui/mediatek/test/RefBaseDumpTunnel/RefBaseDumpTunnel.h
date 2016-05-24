#include <utils/RefBase.h>

namespace android
{

class RefBaseTest: public RefBase{
    public:
        RefBaseTest(){
            RefBaseMonitor::getInstance().monitor(this);
        }
        ~RefBaseTest(){
            RefBaseMonitor::getInstance().unmonitor(this);
        }
};

};
