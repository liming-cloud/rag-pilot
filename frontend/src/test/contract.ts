/**
 * 接口契约（#145 C7）：后端 ApiContractTests 把查询接口的响应换成“形状”写进 contract/api-shapes.json，
 * 这里把 MSW 假数据换成同样的形状再比较。形状：对象是字段名到形状，数组是合并后的元素形状（空数组记为 []），
 * 其余是 "string"、"number"、"boolean"、"null"。
 */
export type Shape = string | Shape[] | { [field: string]: Shape };

export function shapeOf(value: unknown): Shape {
  if (value === null || value === undefined) return "null";
  if (Array.isArray(value)) {
    const merged = value.map(shapeOf).reduce<Shape | null>((left, right) => (left === null ? right : merge(left, right)), null);
    return merged === null ? [] : [merged];
  }
  if (typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([key, field]) => [key, shapeOf(field)]),
    );
  }
  return typeof value === "number" || typeof value === "boolean" ? typeof value : "string";
}

function merge(left: Shape, right: Shape): Shape {
  if (left === "null") return right;
  if (right === "null") return left;
  if (Array.isArray(left) && Array.isArray(right)) {
    if (left.length === 0) return right;
    if (right.length === 0) return left;
    return [merge(left[0]!, right[0]!)];
  }
  if (isObject(left) && isObject(right)) {
    const merged: Record<string, Shape> = { ...left };
    for (const [key, value] of Object.entries(right)) {
      merged[key] = key in merged ? merge(merged[key]!, value) : value;
    }
    return merged;
  }
  return left;
}

function isObject(shape: Shape): shape is { [field: string]: Shape } {
  return typeof shape === "object" && !Array.isArray(shape);
}

function describe(shape: Shape): string {
  if (Array.isArray(shape)) return "数组";
  if (isObject(shape)) return "对象";
  return shape;
}

/**
 * 假数据（actual）和后端（expected）对不上的地方，每条一句话。字段要一一对应；某一边是 null 或空数组时不比较类型，
 * 因为看不出元素或值的类型。
 */
export function mismatches(expected: Shape, actual: Shape, at = "响应"): string[] {
  if (expected === "null" || actual === "null") return [];
  if (Array.isArray(expected) && Array.isArray(actual)) {
    return expected.length === 0 || actual.length === 0 ? [] : mismatches(expected[0]!, actual[0]!, `${at}[]`);
  }
  if (isObject(expected) && isObject(actual)) {
    const problems: string[] = [];
    for (const key of Object.keys(expected)) {
      if (!(key in actual)) problems.push(`${at}.${key}：后端有、假数据没有`);
    }
    for (const key of Object.keys(actual)) {
      if (!(key in expected)) problems.push(`${at}.${key}：假数据有、后端没有`);
      else problems.push(...mismatches(expected[key]!, actual[key]!, `${at}.${key}`));
    }
    return problems;
  }
  return describe(expected) === describe(actual) ? [] : [`${at}：后端是 ${describe(expected)}，假数据是 ${describe(actual)}`];
}
