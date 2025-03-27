package top.guoziyang.mydb.backend.dm.logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.primitives.Bytes;

import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.backend.utils.Parser;
import top.guoziyang.mydb.common.Error;

/**
 * 日志文件读写
 * 
 * 日志文件标准格式为：
 * [XChecksum] [Log1] [Log2] ... [LogN] [BadTail]
 * XChecksum 为后续所有日志计算的Checksum，int类型
 * 
 * 每条正确日志的格式为：
 * [Size] [Checksum] [Data]
 * Size 4字节int 标识Data长度
 * Checksum 4字节int
 */
public class LoggerImpl implements Logger {

    private static final int SEED = 13331;//质数，用于计算checksum的种子

    private static final int OF_SIZE = 0;
    private static final int OF_CHECKSUM = OF_SIZE + 4;
    private static final int OF_DATA = OF_CHECKSUM + 4;
    
    public static final String LOG_SUFFIX = ".log";

    private RandomAccessFile file;
    private FileChannel fc;
    private Lock lock;

    private long position;  // 当前日志指针的位置
    private long fileSize;  // 初始化时记录，log操作不更新
    private int xChecksum;//全局校验

    LoggerImpl(RandomAccessFile raf, FileChannel fc) {
        this.file = raf;
        this.fc = fc;
        lock = new ReentrantLock();
    }

    LoggerImpl(RandomAccessFile raf, FileChannel fc, int xChecksum) {
        this.file = raf;
        this.fc = fc;
        this.xChecksum = xChecksum;
        lock = new ReentrantLock();
    }

    //初始化日志文件
    void init() {
        long size = 0;
        try {
            size = file.length();
        } catch (IOException e) {
            Panic.panic(e);
        }
        //日志文件长度小于4，说明文件损坏
        if(size < 4) {
            Panic.panic(Error.BadLogFileException);
        }

        ByteBuffer raw = ByteBuffer.allocate(4);//分配新的缓存区，大小为4
        try {
            fc.position(0);//设置文件指针位置为0
            fc.read(raw);
        } catch (IOException e) {
            Panic.panic(e);
        }
        int xChecksum = Parser.parseInt(raw.array());//转换为int类型
        this.fileSize = size;
        this.xChecksum = xChecksum;

        checkAndRemoveTail();
    }

    // 检查并移除bad tail
    private void checkAndRemoveTail() {
        rewind();

        int xCheck = 0;
        while(true) {
            byte[] log = internNext();
            if(log == null) break;
            xCheck = calChecksum(xCheck, log);
        }
        if(xCheck != xChecksum) {
            Panic.panic(Error.BadLogFileException);
        }

        try {
            truncate(position);
        } catch (Exception e) {
            Panic.panic(e);
        }
        try {
            file.seek(position);
        } catch (IOException e) {
            Panic.panic(e);
        }
        rewind();
    }

    //计算日志文件的checksum
    private int calChecksum(int xCheck, byte[] log) {
        for (byte b : log) {
            xCheck = xCheck * SEED + b;
        }
        return xCheck;
    }

    //向日志文件写入日志
    @Override
    public void log(byte[] data) {
        byte[] log = wrapLog(data);
        ByteBuffer buf = ByteBuffer.wrap(log);
        lock.lock();
        try {
            fc.position(fc.size());
            fc.write(buf);//写入磁盘
        } catch(IOException e) {
            Panic.panic(e);
        } finally {
            lock.unlock();
        }
        updateXChecksum(log);
    }
    //更新总校验和
    private void updateXChecksum(byte[] log) {
        this.xChecksum = calChecksum(this.xChecksum, log);
        try {
            fc.position(0);
            fc.write(ByteBuffer.wrap(Parser.int2Byte(xChecksum)));
            fc.force(false);
        } catch(IOException e) {
            Panic.panic(e);
        }
    }

    //日志打包
    private byte[] wrapLog(byte[] data) {
        byte[] checksum = Parser.int2Byte(calChecksum(0, data));//生成日志头
        byte[] size = Parser.int2Byte(data.length);//生成日志大小
        return Bytes.concat(size, checksum, data);//拼接日志头和日志数据
    }

    @Override
    public void truncate(long x) throws Exception {
        lock.lock();
        try {
            fc.truncate(x);
        } finally {
            lock.unlock();
        }
    }

    //检验每条日志的检验和对不对
    private byte[] internNext() {
        //检查当前位置是不是大于文件大小
        if(position + OF_DATA >= fileSize) {
            return null;
        }
        //创建缓冲区
        ByteBuffer tmp = ByteBuffer.allocate(4);
        try {
            fc.position(position);//将文件通道的位置设置为当前日志指针
            fc.read(tmp);//从文件通道中读取四个字节的数据到缓冲区，即Size日志文件大小
        } catch(IOException e) {
            Panic.panic(e);
        }
        int size = Parser.parseInt(tmp.array());
        //检查当前位置加上日志的大小是否超过了文件大小，
        if(position + size + OF_DATA > fileSize) {
            return null;
        }
        //
        ByteBuffer buf = ByteBuffer.allocate(OF_DATA + size);
        try {
            fc.position(position);
            fc.read(buf);
        } catch(IOException e) {
            Panic.panic(e);
        }

        byte[] log = buf.array();
        //计算日志的checksum
        int checkSum1 = calChecksum(0, Arrays.copyOfRange(log, OF_DATA, log.length));
        //从日志中读取校验和
        int checkSum2 = Parser.parseInt(Arrays.copyOfRange(log, OF_CHECKSUM, OF_DATA));
        if(checkSum1 != checkSum2) {
            return null;
        }
        position += log.length;
        return log;
    }

    @Override
    public byte[] next() {
        lock.lock();
        try {
            byte[] log = internNext();
            if(log == null) return null;
            return Arrays.copyOfRange(log, OF_DATA, log.length);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void rewind() {
        position = 4;
    }

    @Override
    public void close() {
        try {
            fc.close();
            file.close();
        } catch(IOException e) {
            Panic.panic(e);
        }
    }
    
}
