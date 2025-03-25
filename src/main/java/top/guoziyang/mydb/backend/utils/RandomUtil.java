package top.guoziyang.mydb.backend.utils;

import java.security.SecureRandom;
import java.util.Random;

//随机数工具类
public class RandomUtil {
    public static byte[] randomBytes(int length) {
        Random r = new SecureRandom();//随机数生成器
        byte[] buf = new byte[length];
        r.nextBytes(buf);
        return buf;
    }
}
