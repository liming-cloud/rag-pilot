/** 样例上游模块：声明不依赖任何模块，却引用了下游的 api。 */
@ApplicationModule(allowedDependencies = {})
package com.liming.archmodules.upstream;

import org.springframework.modulith.ApplicationModule;
