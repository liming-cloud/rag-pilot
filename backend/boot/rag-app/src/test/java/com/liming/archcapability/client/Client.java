package com.liming.archcapability.client;

import com.liming.archcapability.capability.Capability;
import com.liming.archcapability.capability.contract.Contract;
import com.liming.archcapability.capability.internal.Internal;

/** 样例调用方：用根包接口和契约是允许的，用没导出的 {@code Internal} 是违规。 */
class Client {

    Capability capability;
    Contract contract;
    Internal internal;
}
