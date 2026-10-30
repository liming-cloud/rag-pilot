/** 后端用 RFC 9457 问题详情返回失败原因：detail 是给用户看的中文说明，code 是稳定的错误码（如 KNOWLEDGE_CONFLICT）。 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
  ) {
    super(message);
  }
}

export async function json<T>(response: Response, fallback: string): Promise<T> {
  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as { detail?: string; code?: string } | null;
    const detail = response.status === 403 ? "没有权限执行此操作，请联系管理员" : (problem?.detail ?? fallback);
    throw new ApiError(response.status, detail, problem?.code);
  }
  return (await response.json()) as T;
}
