package com.liming.archmodules.upstream;

import com.liming.archmodules.downstream.api.DownstreamApi;

/** 违规：上游引用下游 api 包里的类型。 */
class Upstream {

    DownstreamApi downstream;
}
