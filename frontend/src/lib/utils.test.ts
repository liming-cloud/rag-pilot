import { formatDateTime } from "./utils";

describe("formatDateTime", () => {
  it("统一显示为 yyyy-MM-dd HH:mm:ss，个位数补零、24 小时制", () => {
    expect(formatDateTime("2026-01-05T09:03:07")).toBe("2026-01-05 09:03:07");
    expect(formatDateTime("2026-11-25T23:59:59")).toBe("2026-11-25 23:59:59");
  });

  it("带时区的时间按浏览器时区显示", () => {
    const value = "2026-11-05T06:30:00Z";
    const local = new Date(value);
    const expected = `${local.getFullYear()}-${String(local.getMonth() + 1).padStart(2, "0")}-${String(
      local.getDate(),
    ).padStart(2, "0")} ${String(local.getHours()).padStart(2, "0")}:30:00`;
    expect(formatDateTime(value)).toBe(expected);
  });
});
