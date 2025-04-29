package com.hmdp.utils;

public interface ILock {

    public boolean tryLock(long timeoutSeconds);

    void unlock();
}
