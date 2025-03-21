# LiteDB 文档

未完成，持续更新中

## 简介

对MyDB项目的复刻实现。

原作者项目地址：https://github.com/CN-GuoZiyang/MYDB

原作者项目文档：https://shinya.click/projects/mydb/mydb0

本数据库项目实现了以下功能：

- 数据的可靠性和数据恢复
- 两段锁协议（2PL）实现可串行化调度
- MVCC
- 两种事务隔离级别（读提交和可重复读）
- 死锁处理
- 简单的表和字段管理
- 简陋的 SQL 解析（因为懒得写词法分析和自动机，就弄得比较简陋）
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

