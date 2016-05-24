'''
Created on 2014/04/10

@author: MTK04199
'''
import os, sys
import time
import subprocess

import signal


def signal_handler(signal, frame):
        print('Ctrl+C to exit!')
        sys.exit(0)



def externel_cmd(ex_cmd, log = 1):

    if log > 0 :
        p = subprocess.Popen(ex_cmd, shell=True, stderr=subprocess.PIPE)

        ## But do not wait till netstat finish, start displaying output immediately ##
        while True:
            out = p.stderr.read(1)
            if out == '' and p.poll() != None:
                break
            if out != '':
                sys.stdout.write(out)
                sys.stdout.flush()
    else :
        t = os.popen(ex_cmd).read()



if __name__ == '__main__':


    signal.signal(signal.SIGINT, signal_handler)

    serialNum = '0123456789ABCDEF'
    if len(sys.argv) > 1 :
        serialNum = sys.argv[-1]
    #externel_cmd('push_data.bat')

    externel_cmd('run_test.bat %s' %serialNum)

