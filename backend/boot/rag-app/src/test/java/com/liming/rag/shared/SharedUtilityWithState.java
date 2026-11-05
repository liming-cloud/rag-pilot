package com.liming.rag.shared;

/** 违规样例：shared 里公开的“工具类”可以实例化、带实例方法，实际是实现类。 */
public final class SharedUtilityWithState {

    private int calls;

    public SharedUtilityWithState() {
    }

    public int next() {
        return ++calls;
    }
}
