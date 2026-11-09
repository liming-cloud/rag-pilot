# D-004 轻量 DDD 与 MyBatis-Plus 边界（2026-10-25）

聚合只用在 knowledge、answering；其他模块用 Service 加 Mapper。核心模块的聚合是纯 Java 类，DO、Wrapper、Page 只在 infrastructure 包，仓储实现里手写转换。不用 MapStruct。
