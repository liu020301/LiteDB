package top.zehao.litedb.backend.common;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import top.zehao.litedb.common.Error;

/**
 * 引用计数策略的缓存
 */
public abstract class AbstractCache<T> {
    private HashMap<Long, T> cache; // 实际缓存的数据
    private HashMap<Long, Integer> references;  // 元素的引用个数
    private HashMap<Long, Boolean> getting;  // 正在获取某资源的线程

    private int maxResource; // 缓存的最大缓存资源数
    private int count = 0; // 缓存中元素的个数
    private Lock lock;

    public AbstractCache(int maxResource) {
        this.maxResource = maxResource;
        cache = new HashMap<>();
        references = new HashMap<>();
        getting = new HashMap<>();
        lock = new ReentrantLock();
    }

    protected T get(long key) throws Exception {
        while(true) {
            lock.lock();
            if (getting.containsKey(key)){
                // 如果请求的资源正被其他线程获取
                lock.unlock();
                try{
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    continue;
                }
                continue;
            }

            if (cache.containsKey(key)){
                // 资源在缓存中直接返回
                T obj = cache.get(key);
                // 将元素的引用个数加一
                references.put(key, references.getOrDefault(key, 0) + 1);
                lock.unlock();
                return obj;
            }

            // 如果资源不在缓存中，尝试获取该资源
            // 如果缓存已满，抛出异常
            if (maxResource > 0 && count == maxResource){
                lock.unlock();
                throw Error.CacheFullException;
            }
            // 增加缓存元素个数
            count++;
            // 将key设置为被获取中
            getting.put(key, Boolean.TRUE);
            lock.unlock();
            break;
        }

        T obj = null;
        try {
            // 当资源不在缓存时获取资源
            obj = getForCache(key);
        } catch (Exception e) {
            // 获取失败则回滚并抛出异常
            lock.lock();
            count--;
            getting.remove(key);
            lock.unlock();
            throw e;
        }

        //将获取到的资源添加到缓存中并设置引用计数为1
        lock.lock();
        getting.remove(key);
        cache.put(key, obj);
        references.put(key, 1);
        lock.unlock();
        return obj;
    }

    /**
     * 强行释放一个缓存
     */
    protected void release(long key){
        lock.lock();
        try {
            int ref = references.get(key) - 1;
            if (ref == 0){
                T obj = cache.get(key);
                releaseForCache(obj);
                references.remove(key);
                cache.remove(key);
                count--;
            }
            else {
                references.put(key, ref);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关闭缓存，写回所有资源
     */
    protected void close() {
        lock.lock();
        try {
            Set<Long> keys = cache.keySet();
            for (long key : keys) {
                T obj = cache.get(key);
                releaseForCache(obj);
                references.remove(key);
                cache.remove(key);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 当资源不在缓存时的获取资源行为
     */
    protected abstract T getForCache(long key) throws Exception;

    /**
     * 当资源被驱逐时的写回行为
     */
    protected abstract void releaseForCache(T obj);
}
