package com.wherelee.base.modules.llm;

import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmResponse;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.platform.common.BizException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** P0 骨架：契约已就位，调用实现按迭代 P1/P2 补充。 */
@Service
public class LlmService implements LlmOperations {

    @Override
    public LlmResponse invoke(LlmRequest request) {
        throw new BizException(HttpStatus.NOT_IMPLEMENTED, "LLM 调用尚未实现（P0 骨架）");
    }

    @Override
    public void stream(LlmRequest request, LlmStreamSink sink) {
        throw new BizException(HttpStatus.NOT_IMPLEMENTED, "LLM 流式调用尚未实现（P0 骨架）");
    }
}
