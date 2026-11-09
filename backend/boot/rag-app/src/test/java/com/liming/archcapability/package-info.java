/**
 * 能力组件导出范围的样例模块（#187）：只给 {@code ModularityTests} 确认能力组件的子包默认不对外，只有标了
 * {@code @NamedInterface} 的类型能被依赖。放在 {@code com.liming.rag} 之外，不影响真实模块的检查。
 */
package com.liming.archcapability;
