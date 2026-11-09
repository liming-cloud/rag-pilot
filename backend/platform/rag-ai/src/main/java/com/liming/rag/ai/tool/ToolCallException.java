package com.liming.rag.ai.tool;

import com.liming.rag.shared.BusinessException;
import com.liming.rag.shared.ErrorKind;
import java.io.Serial;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.NamedInterface;

/**
 * 工具调用失败，错误码 {@code TOOL_CALL_FAILED}。message 给用户看，不含地址、令牌和客户系统的原始响应；
 * 调用方一律回答“暂时查不到”，不编造数据（规范第 6 节）。
 */
@NamedInterface("tool")
public class ToolCallException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 失败的原因分类。 */
    public enum Reason {
        /** 没配置密钥或连接。 */
        NOT_CONFIGURED,
        /** 连不上、超时、502/503/504（已重试过）。 */
        UNAVAILABLE,
        /** 客户系统返回 401/403：令牌被拒。 */
        REJECTED,
        /** 客户系统返回 429。 */
        RATE_LIMITED,
        /** 工具返回 {@code isError:true}，{@link #toolCode()} 是它给的错误码。 */
        TOOL_ERROR,
        /** 协议错误：未知工具、请求格式错、版本不支持、返回不符合约定。 */
        PROTOCOL
    }

    private final Reason reason;
    private final @Nullable String toolCode;
    private final @Nullable Duration retryAfter;

    ToolCallException(Reason reason, String message, @Nullable String toolCode, @Nullable Duration retryAfter,
            @Nullable Throwable cause) {
        super(reason == Reason.RATE_LIMITED ? ErrorKind.TOO_MANY_REQUESTS : ErrorKind.UNAVAILABLE,
                "TOOL_CALL_FAILED", message, cause);
        this.reason = reason;
        this.toolCode = toolCode;
        this.retryAfter = retryAfter;
    }

    public Reason reason() {
        return reason;
    }

    /** 工具给的业务错误码（{@code out_of_scope}、{@code invalid_argument} 等），只有 {@link Reason#TOOL_ERROR} 才有。 */
    public @Nullable String toolCode() {
        return toolCode;
    }

    /** 客户系统要求的等待时间，只有 {@link Reason#RATE_LIMITED} 且给了 Retry-After 才有。 */
    public @Nullable Duration retryAfter() {
        return retryAfter;
    }
}
