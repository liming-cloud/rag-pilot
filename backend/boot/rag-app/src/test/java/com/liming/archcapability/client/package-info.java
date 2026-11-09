/** 样例调用方：声明依赖能力的根包和契约，却引用了没导出的内部类型。 */
@ApplicationModule(allowedDependencies = {"capability", "capability :: contract"})
package com.liming.archcapability.client;

import org.springframework.modulith.ApplicationModule;
