package top.zehao.litedb.backend.dm.page;

import top.zehao.litedb.backend.dm.pageCache.PageCache;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PageImpl implements Page{
    private int pageNumber;  //页面的页号从1开始
    private byte[] data;  //实际包含的字节数据
    private boolean dirty;  //标志着页面是否是脏页面
    private Lock lock;  //用于页面的锁
    private PageCache pc;  //PageCache的引用，用来方便再拿到Page引用时可以快速对这个页面的缓存进行释放操作


    public PageImpl(int pageNumber, byte[] data, PageCache pc) {
        this.pageNumber = pageNumber;
        this.data = data;
        this.pc = pc;
        lock = new ReentrantLock();
    }

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }

    public void release() {
        pc.release(this);
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }
    public boolean isDirty() {
        return dirty;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public byte[] getData() {
        return data;
    }





}
