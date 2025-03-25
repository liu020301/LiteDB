# LiteDB 文档

未完成，持续更新中。

## 简介

对MyDB项目的复刻实现。本文档仅作为个人学习使用。

原作者项目地址：https://github.com/CN-GuoZiyang/MYDB

原作者项目文档：https://shinya.click/projects/mydb/mydb0

本数据库项目实现了以下功能：

- 数据的可靠性和数据恢复
- 两段锁协议（2PL）实现可串行化调度
- MVCC
- 两种事务隔离级别（读提交和可重复读）
- 死锁处理
- 简单的表和字段管理
- 简陋的 SQL 解析
- 基于 socket 的 server 和 client



## 整体结构

LiteDB项目分为前端和后端，前后段通过socket交互。前端读取用户输入发送到后端执行，输出返回结果并等待下一次输入。后端会解析SQL语句，如果是合法的SQL语句，则执行返回结果。后端部分分为五个模块：

1. Transaction Manager (TM)
2. Data Manager (DM)
3. Version Manager (VM)
4. Index Manager (IM)
5. Table Manager (TBM)

各部分职责：

1. TM负责通过XID（每个事务都有一个XID）来维护事务状态，并提供接口供其他模块检查某个事务状态。

2. DM管理数据库DB文件和日志文件。DM主要职责：1）分页管理DB文件并缓存；2）管理日志文件，发生错误时可以根据日志恢复；3）将DB文件抽象为DataItem供上层模块使用并提供缓存。
3. VM
4. IM
5. TBM





## 1. Tansaction Manager (TM)

TM通过操作XID（每个事务都有一个XID）来维护事务状态，并提供接口供其他模块检查某个事务状态。

### 结构

TM主要负责管理事务的生命周期，包括：

* 事务创建 begin()
* 事务提交 commit()
* 事务回滚 abort()
* 事务状态查询：isActive() / isCommitted() / isAborted()

其通过XID文件持久化事务状态，利用文件锁（ReetrantLock）确保xidCounter在并发环境下安全更新。



### XID文件（.xid）

每一个事务都有一个XID，其唯一标识了这个事务。事务XID从1开始自增，且不可重复。XID 0是一个超级事务，XID为0的事务状态永远是committed。

* 前8字节：存储xidCounter（当前最大事务ID/当前事务个数）

* 事务XID在文件中的状态存储于 '(xid - 1) + 8' 字节处，xid - 1因为XID 0的状态不需要记录。

* 每个事务占1字节，其value代表了事务状态：
  ``` Java
  private static final byte FIELD_TRAN_ACTIVE = 0;    // 活跃
  private static final byte FIELD_TRAN_COMMITTED = 1; // 已提交
  private static final byte FIELD_TRAN_ABORTED = 2;   // 已回滚
  ```

Transaction Manager提供了一些接口用于创建事务和查询事务状态。

```Java
public interface TransactionManager {
    long begin();
    void commit(long xid);
    void abort(long xid);
    boolean isActive(long xid);
    boolean isCommitted(long xid);
    boolean isAborted(long xid);
    void close(); // 关闭TM
  	...
}
```

其中还有两个静态方法：

* create() ：创建一个xid文件并创建TM。从零创建xid文件时需要写一个空的xid文件头，即xidCounter设置为0。
* open() ：从一个已有的xid文件来创建TM。



### 文件读写

文件的读写采用了NIO方式的FileChannel。

FileChannel: 用于文件I/O的通道，支持文件的读写和追加操作。允许在文件的任意位置进行数据传输，支持文件锁定以及内存映射等高级功能。FileChannel 无法设置为非阻塞模式，因此它只适用于阻塞式文件操作。

Channel通道只负责传输数据，不直接操作数据。操作数据是通过ByteBuffer。



### XID校验

构造函数创建了一个TM后，首先要对XID文件进行校验。其通过文件头的8字节反推文件的理论长度，于实际长度做对比。如果不同则XID文件不合法。

```Java
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
```



### 开启事务 (begin())

begin() 方法会开始一个事务。其首先设置 xidCounter+1 事务（新事务）的状态为active，随后xidCounter自增，更新文件头。

```Java
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
```



### 提交与回滚事务 (commit() & abort())

提交和回滚就借助updateXID来更新其status。

```Java
// 将xid事务状态设置为committed
public void commit(long xid) {
    updateXID(xid, FIELD_TRAN_COMMITTED);
}

// 将xid事务状态设置为aborted
public void abort(long xid) {
    updateXID(xid, FIELD_TRAN_ABORTED);
}
```



### 检查XID状态

通过一个chekXID方法来检查status。

```Java
private boolean checkXID(long xid, byte status) {
    long offset = getXidPosition(xid);
    ByteBuffer buffer = ByteBuffer.wrap(new byte[XID_FIELD_SIZE]);
    try {
        fileChannel.position(offset);
        fileChannel.read(buffer);
    } catch (IOException e) {
        Panic.panic(e);
    }
    return buffer.array()[0] == status;
}
```

其他的isActive(), isCommitted(), isAborted()则通过此方法来实现。

```Java
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
```





## 2. Data Manager

DM功能归为两点：

1. 上层模块和文件系统之间的一个抽象层，向下直接读写文件，向上提供数据的包装。
2. 日志功能。

无论是向上还是向下，都提供了一个缓存功能，用内存操作来保证效率。

### 引用计数缓存框架

#### 为什么不用LRU？

> 原文
>
> 由于分页管理和数据项（DataItem）管理都涉及缓存，这里设计一个更通用的缓存框架。
>
> 看到这里，估计你们也开始犯嘀咕了，为啥使用引用计数策略，而不使用 “极为先进的” LRU 策略呢？
>
> 这里首先从缓存的接口设计说起，如果使用 LRU 缓存，那么只需要设计一个 `get(key)` 接口即可，释放缓存可以在缓存满了之后自动完成。设想这样一个场景：某个时刻缓存满了，缓存驱逐了一个资源，这时上层模块想要将某个资源强制刷回数据源，这个资源恰好是刚刚被驱逐的资源。那么上层模块就发现，这个数据在缓存里消失了，这时候就陷入了一种尴尬的境地：是否有必要做回源操作？
>
> 1. 不回源。由于没法确定缓存被驱逐的时间，更没法确定被驱逐之后数据项是否被修改，这样是极其不安全的
> 2. 回源。如果数据项被驱逐时的数据和现在又是相同的，那就是一次无效回源
> 3. 放回缓存里，等下次被驱逐时回源。看起来解决了问题，但是此时缓存已经满了，这意味着你还需要驱逐一个资源才能放进去。这有可能会导致缓存抖动问题
>
> 当然我们可以记录下资源的最后修改时间，并且让缓存记录下资源被驱逐的时间。但是……
>
> > 如无必要，无增实体。 —— 奥卡姆剃刀
>
> 问题的根源还是，LRU 策略中，资源驱逐不可控，上层模块无法感知。而引用计数策略正好解决了这个问题，只有上层模块主动释放引用，缓存在确保没有模块在使用这个资源了，才会去驱逐资源。

这里的**回源操作**指的是写回磁盘。数据项原本在缓存里，正在被使用，可能被修改；结果被 LRU 自动驱逐了，上层现在想写回磁盘 —— 却发现它已经没了。

#### 实现

```AbstractCache<T>```抽象类位于**common**包中，其包含两个抽象方法留给实现类去实现具体的操作。

```java
/**
 * 当资源不在缓存时的获取行为
 */
protected abstract T getForCache(long key) throws Exception;
/**
 * 当资源被驱逐时的写回行为
 */
protected abstract void releaseForCache(T obj);
```

##### 引用计数

除了缓存功能，还要维护一个计数。除此之外，为了应对多线程场景，还需要记录哪些资源正在从数据源中获取。通过三个map：

1. `private HashMap<long, T> cache;`这个HashMap对象用于存储实际缓存的数据。Key是资源的唯一标识符（通常是资源id或哈希值），value是缓存的资源对象（类型T）。
2. `private HashMap<Long, Integer> references;` 这个HashMap对象用于记录每个资源的引用个数。Key是唯一标识符，value是一个整数，表示当前的引用计数。
3. `private HashMap<Long, Boolean> getting;`这个HashMap对象用于记录哪些资源当前正在从数据源中获取。Key是资源唯一标识符，value是布尔值，表示该资源是否正在被获取。在多线程环境下，当某个线程试图从数据源获取资源时，需要标记该资源正在被获取，以免其他线程重复获取相同资源。

```java
private HashMap<Long, T> cache;                     // 实际缓存的数据
private HashMap<Long, Integer> references;          // 资源的引用个数
private HashMap<Long, Boolean> getting;             // 正在被获取的资源
```

##### get()

通过get()方法获取资源，在循环中无限尝试从缓存里获取。首先要检查是否有其他线程正在从数据源获取这个资源，如果有就过会儿再来看看。如果资源在缓存中，就可以直接获取并返回了，同时给资源引用数+1。否则如果缓存没满的话，就在getting中注册一下，该线程准备从数据源获取资源了。

```Java
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
    //并且因为已经获取完成，从getting中删除key
    lock.lock();
    getting.remove(key);
    cache.put(key, obj);
    references.put(key, 1);
    lock.unlock();
    return obj;
}

```

##### release()

释放内存，本质是：”我这边用完了，但不代表别人也用完了“，所以直接从references中 -1，如果减到0了，就可以回源并且删除缓存中所有相关结构了。

```java
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
```

##### close()

安全关闭的功能，关闭时将所有资源强行回源。

```java
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
```

到这里一个简单的缓存框架就实现了，其他的缓存只需要继承这个类，并实现两个抽象方法即可。



### 共享内存数组

> 原文
>
> 这里得提一个 Java 很蛋疼的地方。
>
> Java 中，将数组看作一个对象，在内存中，也是以对象的形式存储的。而 c、cpp 和 go 之类的语言，数组是用指针来实现的。这就是为什么有一种说法：
>
> > 只有 Java 有真正的数组
>
> 但这对这个项目似乎不是一个好消息。譬如 golang，可以执行下面语句：
>
> go
>
> ```go
> var array1 [10]int64
> array2 := array1[5:]
> ```
>
> 这种情况下，array2 和 array1 的第五个元素到最后一个元素，是共用同一片内存的，即使这两个数组的长度不同。
>
> 这在 Java 中是无法实现的（什么是高级语言啊~）。
>
> 在 Java 中，当你执行类似 subArray 的操作时，只会在底层进行一个复制，无法同一片内存。
>
> 于是，我写了一个 SubArray 类，来（松散地）规定这个数组的可使用范围：
>
> java
>
> ```java
> public class SubArray {
>     public byte[] raw;
>     public int start;
>     public int end;
> 
>     public SubArray(byte[] raw, int start, int end) {
>         this.raw = raw;
>         this.start = start;
>         this.end = end;
>     }
> }
> ```



### 页面缓存

> 前言（原文）
>
> 本节主要内容就是 DM 模块向下对文件系统的抽象部分。DM 将文件系统抽象成页面，每次对文件系统的读写都是以页面为单位的。同样，从文件系统读进来的数据也是以页面为单位进行缓存的。

默认将数据页大小定为8K。上面已经实现了一个通用的缓存框架，这里就可以直接借用哪个缓存框架。

#### 定义页面结构

页面是存储在内存的数据单元。

* pageNumber：页面的页号，从1开始。
* data：实际包含的字节数据。
* dirty：标志着页面是否是脏页面，缓存驱逐时要将脏页面写回磁盘。脏页面指修改了但还没写入磁盘的页面。
* lock：用于页面的锁
* pc：一个PageCache的引用，用来方便在拿到Page的引用时可以快速对这个页面的缓存进行释放操作。

```java
public class PageImpl implements Page {
    private int pageNumber;
    private byte[] data;
    private boolean dirty;
    private Lock lock;
    private PageCache pc;
}
```

#### 定义页面缓存的接口

```java
public interface PageCache {
    int newPage(byte[] initData);
    Page getpage(int pgno) throws Exception;
    void close();
    void release(Page page);

    void truncateByBgno(int maxPgno);
    int getPageNumber();
    void flushPage(Page page);

}
```

页面缓存的具体实现类要继承抽象缓存框架，并且实现getForCache() 和 releaseForCache() 两个抽象方法。由于数据源就是文件系统，getForCache()直接从文件中读取并包裹成Page：

```java 
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
```

releaseForCache驱逐页面时，只需要根据页面是否是脏页面，来决定是否需要写回文件系统。是脏页面就写回，不是脏页面的话，意味着文件没有修改，就不用写回了。

```java
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
```

PageCache使用了一个AtomicInteger，记录了当前打开的数据库文件有多少页，在数据库文件打开时就会被计算，并在新建页面时自增。

```java
public int newPage(byte[] initData) {
    int pgno = pageNumbers.incrementAndGet();
    Page pg = new PageImpl(pgno, initData, null);
    flush(pg);  // 新建的页面要立刻写回
    return pgno;
}
```

