package top.zehao.litedb.backend.tm;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.zehao.litedb.backend.utils.Panic;
import top.zehao.litedb.backend.utils.Parser;
import top.zehao.litedb.common.Error;


public class TransactionManagerImpl implements TransactionManager {
    // XID文件开头8字节表示事务个数
    static final int LEN_XID_HEADER_LENGTH = 8;
    // 每个事务XID长度为1
    private static final int XID_FIELD_SIZE = 1;
    // 三种事务状态
    private static final byte FIELD_TRAN_ACTIVE = 0;
    private static final byte FIELD_TRAN_COMMITTED = 1;
    private static final byte FIELD_TRAN_ABORTED = 2;
    // 超级事务XID=0 Always Committed
    public static final long SUPER_XID = 0;
    // XID 文件后缀
    static final String XID_SUFFIX = ".xid";

    private RandomAccessFile file;
    private FileChannel fileChannel;
    private long xidCounter;
    private Lock counterLock;

    TransactionManagerImpl(RandomAccessFile file, FileChannel fileChannel) {
        this.file = file;
        this.fileChannel = fileChannel;
        counterLock = new ReentrantLock();
        checkXIDCounter();
    }

    /**
     * 检查XID文件是否合法
     * 读取XID_FILE_HEADER中的xidcounter，计算文件的理论长度，对比实际长度
     */
    private void checkXIDCounter() {
        long fileLen = 0;
        try {
            fileLen = file.length();
        } catch (IOException e1){
            Panic.panic(Error.BadXIDFileException);
        }

        if (fileLen < LEN_XID_HEADER_LENGTH) {
            Panic.panic(Error.BadXIDFileException);
        }

        ByteBuffer buffer = ByteBuffer.allocate(LEN_XID_HEADER_LENGTH);
        try {
            fileChannel.position(0);
            fileChannel.read(buffer);
        } catch (IOException e) {
            Panic.panic(e);
        }
        this.xidCounter = Parser.parseLong(buffer.array());
        long end = getXidPosition(this.xidCounter + 1);
        if (end != fileLen) {
            Panic.panic(Error.BadXIDFileException);
        }
    }

    // 根据事务xid取得其在xid文件中的位置
    private long getXidPosition(long xid) {
        return LEN_XID_HEADER_LENGTH + (xid-1) * XID_FIELD_SIZE;
    }

    public long begin() {
        counterLock.lock();
        try {
            // 新XID
            long xid = xidCounter + 1;
            // 更新新建XID状态为active
            updateXID(xid, FIELD_TRAN_ACTIVE);
            // 讲XID加一，并更新XID Header
            incrXIDCounter();
            return xid;
        } finally {
            counterLock.unlock();
        }
    }

    // 用于更新xid事务状态为status
    private void updateXID(long xid, byte status) {
        long offset = getXidPosition(xid);
        byte[] tmp = new byte[XID_FIELD_SIZE];
        tmp[0] = status;
        ByteBuffer buffer = ByteBuffer.wrap(tmp);
        try {
            // 在offset位置开始写入新的xid事务status
            fileChannel.position(offset);
            fileChannel.write(buffer);
        } catch (IOException e) {
            Panic.panic(e);
        }
        try {
            // 强制写入磁盘
            fileChannel.force(false);
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    // 将XID加一，并更新XID Header
    private void incrXIDCounter() {
        xidCounter++;
        // 将xidCounter整合为byte类型存入buffer
        //
        ByteBuffer buffer = ByteBuffer.wrap(Parser.long2Byte(xidCounter));
        try {
            // 从开头开始更新XID Header
            fileChannel.position(0);
            fileChannel.write(buffer);
        } catch (IOException e) {
            Panic.panic(e);
        }
        try {
            fileChannel.force(false);
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    // 将xid事务状态设置为committed
    public void commit(long xid) {
        updateXID(xid, FIELD_TRAN_COMMITTED);
    }

    // 将xid事务状态设置为aborted
    public void abort(long xid) {
        updateXID(xid, FIELD_TRAN_ABORTED);
    }

    // 检查XID事务是否处于status状态
    private boolean checkXID(long xid, byte status) {
        long offset = getXidPosition(xid);
        //
        ByteBuffer buffer = ByteBuffer.wrap(new byte[XID_FIELD_SIZE]);
        try {
            fileChannel.position(offset);
            fileChannel.read(buffer);
        } catch (IOException e) {
            Panic.panic(e);
        }
        return buffer.array()[0] == status;
    }

    public boolean isActive(long xid) {
        if (xid == SUPER_XID) {
            return false;
        }
        return checkXID(xid, FIELD_TRAN_ACTIVE);
    }

    public boolean isCommitted(long xid) {
        if (xid == SUPER_XID) {
            return true;
        }
        return checkXID(xid, FIELD_TRAN_COMMITTED);
    }

    public boolean isAborted(long xid) {
        if (xid == SUPER_XID) {
            return false;
        }
        return checkXID(xid, FIELD_TRAN_ABORTED);
    }

    public void close() {
        try {
            fileChannel.close();
            file.close();
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

}
