package cn.hwz.learn.mqblocking.tool;

import java.util.Random;

/**
 * @author needleHuo
 * @version jdk11
 * @description
 * @since 2025/3/15
 */
public class ThreadSleep {
    public static void sleep(int n){
        try {
            Thread.sleep(n* 1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void sleep(double n){
        try {
            long v = (long)n * 1000;
            Thread.sleep(v);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void ranSleep(int maxMills){
        Random random = new Random();
        try {
            int i = random.nextInt(maxMills);
            Thread.sleep(i);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
