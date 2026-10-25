package com.liming.rag.common;

/**
 * 用户标识。各模块之间只传递标识，不传递用户实体，避免跨模块共享可变对象。
 *
 * @param value 数据库中的用户主键，必须为正数
 */
public record UserId(long value) {

    /**
     * @throws IllegalArgumentException 当标识不是正数时
     */
    public UserId {
        if (value <= 0) {
            throw new IllegalArgumentException("用户标识必须为正数");
        }
    }
}
