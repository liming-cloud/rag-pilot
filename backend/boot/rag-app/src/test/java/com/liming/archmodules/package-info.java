/**
 * 违反依赖方向的样例模块（#136）：只给 {@code ModularityTests} 确认 {@code allowedDependencies} 能拦下反向依赖。
 * 放在 {@code com.liming.rag} 之外，不影响真实模块的检查。
 */
package com.liming.archmodules;
