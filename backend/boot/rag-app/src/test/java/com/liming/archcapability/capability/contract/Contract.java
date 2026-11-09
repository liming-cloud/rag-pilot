package com.liming.archcapability.capability.contract;

import org.springframework.modulith.NamedInterface;

/** 样例契约类型：标了 {@code @NamedInterface}，可以被依赖。 */
@NamedInterface("contract")
public record Contract(String value) {
}
