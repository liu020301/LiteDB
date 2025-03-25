package top.zehao.litedb.backend.dm.pageCache;

import top.zehao.litedb.backend.common.AbstractCache;
import top.zehao.litedb.backend.dm.page.Page;
import top.zehao.litedb.backend.dm.page.PageImpl;
import top.zehao.litedb.backend.utils.Panic;
import top.zehao.litedb.common.Error;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PageCacheImpl 负责管理数据库页的缓存。
 * 继承自 AbstractCache<Page>，使用引用计数控制页的加载、释放、写回。
 */
public class PageCacheImpl extends AbstractCache<Page> implements PageCache {
    private static final int MEM_MIN_LIMIT = 10; // 最小缓存页数限制
    public static final String DB_SUFFIX = ".db"; // 数据文件后缀

    private RandomAccessFile file; // 数据库文件对象
    private FileChannel fc;        // 文件通道，用于高效 I/O
    private Lock fileLock;         // 文件锁，保证并发安全

    private AtomicInteger pageNumbers; // 当前最大页号（用于 newPage 时递增）

    /**
     * 构造 PageCacheImpl
     * @param file         .db 文件对象
     * @param fileChannel  文件通道
     * @param maxResource  缓存页最大数量
     */
    PageCacheImpl(RandomAccessFile file, FileChannel fileChannel, int maxResource) {
        super(maxResource);
        if (maxResource < MEM_MIN_LIMIT) {
            Panic.panic(Error.MemTooSmallException);
        }
        long length = 0;
        try {
            length = file.length();
        } catch (Exception e){
            Panic.panic(e);
        }
        this.file = file;
        this.fc = fileChannel;
        this.fileLock = new ReentrantLock();
        this.pageNumbers = new AtomicInteger((int)length / PAGE_SIZE); // 推算出已有多少页
    }

    /**
     * 创建一个新页并写入初始数据，返回新页的页号
     */
    public int newPage(byte[] initData) {
        int pgno = pageNumbers.incrementAndGet();
        Page page = new PageImpl(pgno, initData, null);
        flush(page); // 新建的页面要立即写回
        return pgno;
    }

    /**
     * 获取页对象（优先从缓存获取，缓存中不存在则从磁盘读取）
     */
    public Page getPage(int pgno) throws Exception {
        return get((long) pgno);
    }


    /**
     * 当缓存未命中时，根据pageNumber从数据库文件中读取页数据，并包裹成Page。
     */
    @Override
    protected Page getForCache(long key) throws Exception {
        int pgno = (int)key;
        long offset = PageCacheImpl.pageOffset(pgno);
        ByteBuffer buffer = ByteBuffer.allocate(PAGE_SIZE);
        fileLock.lock();
        try {
            fc.position(offset);
            fc.read(buffer); // 读取 PAGE_SIZE 字节
        } catch (IOException e) {
            Panic.panic(e);
        }
        fileLock.unlock();
        return new PageImpl(pgno, buffer.array(), this); // 封装为 Page 对象并绑定缓存引用
    }

    /**
     * 当页从缓存中被释放时，如果是脏页则写回磁盘
     */
    @Override
    protected void releaseForCache(Page page) {
        if (page.isDirty()) {
            flush(page);
            page.setDirty(false);
        }
    }

    /**
     * 释放页缓存（将引用计数 -1）
     */
    public void release(Page page) {
        release((long)page.getPageNumber());
    }

    /**
     * 强制将页写回磁盘，无论是否为脏页
     */
    public void flushPage(Page page) {
        flush(page);
    }

    /**
     * 将页内容写入磁盘
     */
    private void flush(Page page) {
        int pgno = page.getPageNumber();
        long offset = PageCacheImpl.pageOffset(pgno);

        fileLock.lock();
        try {
            ByteBuffer buffer = ByteBuffer.wrap(page.getData());
            fc.position(offset);
            fc.write(buffer);
            fc.force(false);
        } catch (IOException e) {
            Panic.panic(e);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * 截断数据库文件到指定最大页号，通常用于删除多余数据页
     */
    public void truncateByBgno(int maxPgno) {
        long size = pageOffset(maxPgno + 1); // 截断点 = 下一页的起始位置
        try {
            file.setLength(size); // 设置文件长度
        } catch (IOException e) {
            Panic.panic(e);
        }
        pageNumbers.set(maxPgno); // 更新最大页号
    }

    /**
     * 关闭缓存系统（包括文件、通道等资源）
     */
    @Override
    public void close() {
        super.close();
        try {
            fc.close();
            file.close();
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    /**
     * 获取当前数据库中页的总数量
     */
    public int getPageNumber() {
        return pageNumbers.get();
    }

    /**
     * 根据页号计算其在文件中的偏移量
     * PageNumber 从 1 开始计数
     */
    private static long pageOffset(int pgno) {
        // PageNumber从1开始
        return (pgno - 1) * PAGE_SIZE;
    }
}
