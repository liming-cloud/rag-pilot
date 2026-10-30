package com.liming.rag.shared;

/**
 * 业务错误的分类，所有上下文共用。它只说明“是哪一类失败”，转成 HTTP 状态码由 rag-app 的入口负责，共享内核不涉及协议。
 */
public enum ErrorKind {

    /** 输入不符合规则，调用方修改后可以重试。 */
    INVALID,
    /** 没有登录，或登录凭据不对。 */
    UNAUTHENTICATED,
    /** 已登录，但没有权限做这件事。 */
    FORBIDDEN,
    /** 要操作的对象不存在，或对提问人不可见（不区分这两种，避免泄露存在性）。 */
    NOT_FOUND,
    /** 与已有数据冲突，例如重名。 */
    CONFLICT,
    /** 不支持的内容，例如上传了不支持的文件类型。 */
    UNSUPPORTED,
    /** 依赖的外部服务暂时不可用，稍后可以重试。 */
    UNAVAILABLE
}
