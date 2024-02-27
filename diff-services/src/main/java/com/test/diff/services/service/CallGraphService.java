package com.test.diff.services.service;

import com.test.diff.services.params.CallGraphParams;

public interface CallGraphService {

    /**
     * 刷新调用数据库
     * @param params
     */
    void refreshDB(CallGraphParams params);

    com.test.diff.services.base.controller.result.BaseResult findCaller(CallGraphParams params);

    com.test.diff.services.base.controller.result.BaseResult findCallee(CallGraphParams params);
}
