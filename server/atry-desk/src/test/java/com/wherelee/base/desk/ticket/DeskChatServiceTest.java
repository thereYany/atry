package com.wherelee.base.desk.ticket;

import com.wherelee.base.desk.billing.DeskBillingSupport;
import com.wherelee.base.desk.config.DeskProperties;
import com.wherelee.base.modules.contracts.llm.LlmError;
import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.LlmStreamSink;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.platform.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 首答链路单元测试：上下文拼装、成功落库、失败不落库、越权拒绝。
 *
 * <p>内核的治理/计费由 atry-llm 自己的测试覆盖，这里只验证垂直域编排是否正确
 * （哪些消息进入上下文、成功/失败时落库与回调的差异）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeskChatServiceTest {

    private static final long TENANT = 1L;
    private static final long USER = 7L;
    private static final long OTHER_USER = 8L;
    private static final long TICKET_ID = 10L;
    private static final String TICKET_NO = "D20261120000000000001";

    @Mock
    private DeskTicketRepository repository;
    @Mock
    private DeskBillingSupport billing;
    @Mock
    private LlmOperations llm;

    private DeskChatService service;
    private final DeskProperties properties = new DeskProperties(2L, "mock-small", 256, 10,
            new BigDecimal("20.000000"), "CNY");

    @BeforeEach
    void setUp() {
        service = new DeskChatService(repository, billing, properties, llm);
        when(repository.findTicket(TENANT, TICKET_NO)).thenReturn(Optional.of(ticket(USER)));
        when(repository.listMessages(TENANT, TICKET_ID, properties.historyLimit()))
                .thenReturn(List.of(
                        message("USER", "我改完密码还是登录不上"),
                        message("ASSISTANT", "请确认是否使用了新密码。"),
                        message("SYSTEM", "内部备注不应进入上下文")));
    }

    @Test
    void 成功路径_落库用量成本并回调done() {
        when(billing.subject(TENANT, USER)).thenReturn(new SubjectRef(TENANT, 2L, USER));
        when(billing.ensureAccount(any())).thenReturn(null);
        when(billing.cost(any(), eq("mock-small"), any())).thenReturn(new BigDecimal("0.000123"));
        when(billing.balance(TENANT, USER)).thenReturn(balance("19.999877"));
        doAnswer(invocation -> {
            LlmStreamSink sink = invocation.getArgument(1);
            sink.onChunk("请先");
            sink.onChunk("确认新密码。");
            sink.onComplete(new LlmUsage(12, 20, 4, 0, 0, LlmUsage.Source.PROVIDER), "stop");
            return null;
        }).when(llm).stream(any(LlmRequest.class), any(LlmStreamSink.class));

        RecordingListener listener = new RecordingListener();
        service.streamReply(TENANT, USER, TICKET_NO, " 还是不行 ", listener);

        // 用户消息先落库
        verify(repository).appendMessage(TENANT, TICKET_ID, "USER", "还是不行");
        // 上下文 = system + 历史 USER/ASSISTANT（SYSTEM 备注被过滤）
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).stream(requestCaptor.capture(), any(LlmStreamSink.class));
        LlmRequest request = requestCaptor.getValue();
        assertThat(request.messages()).hasSize(3);
        assertThat(request.messages().get(0).role()).isEqualTo("system");
        assertThat(request.messages().get(1).role()).isEqualTo("user");
        assertThat(request.messages().get(2).role()).isEqualTo("assistant");
        assertThat(request.budgetRef()).isEqualTo("desk");
        assertThat(request.appId()).isEqualTo(2L);
        assertThat(request.stream()).isTrue();

        // 成功：回复落库（usage 12/20，成本 0.000123 CNY → 123 micros）
        verify(repository).appendAssistantMessage(eq(TENANT), eq(TICKET_ID), eq("请先确认新密码。"),
                anyString(), eq(12), eq(20), eq(123L));
        assertThat(listener.deltas).containsExactly("请先", "确认新密码。");
        assertThat(listener.done).isNotNull();
        assertThat(listener.done.content()).isEqualTo("请先确认新密码。");
        assertThat(listener.done.usage().totalTokens()).isEqualTo(36);
        assertThat(listener.done.cost()).isEqualByComparingTo("0.000123");
        assertThat(listener.done.balance().balance()).isEqualByComparingTo("19.999877");
        assertThat(listener.error).isNull();
    }

    @Test
    void LLM失败_只回调error不落库() {
        when(billing.subject(TENANT, USER)).thenReturn(new SubjectRef(TENANT, 2L, USER));
        when(billing.ensureAccount(any())).thenReturn(null);
        doThrow(new LlmException(new LlmError(LlmErrorCategory.QUOTA_EXHAUSTED, "budget",
                false, false, "预算预扣失败：余额不足")))
                .when(llm).stream(any(LlmRequest.class), any(LlmStreamSink.class));

        RecordingListener listener = new RecordingListener();
        service.streamReply(TENANT, USER, TICKET_NO, "在吗", listener);

        assertThat(listener.error).isNotNull();
        assertThat(listener.error.category()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(listener.done).isNull();
        assertThat(listener.deltas).isEmpty();
        verify(repository, never()).appendAssistantMessage(anyLong(), anyLong(), anyString(),
                anyString(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void 非本人工单_直接拒绝且不调用LLM() {
        when(repository.findTicket(TENANT, TICKET_NO)).thenReturn(Optional.of(ticket(OTHER_USER)));

        RecordingListener listener = new RecordingListener();
        assertThatThrownBy(() -> service.streamReply(TENANT, USER, TICKET_NO, "越权", listener))
                .isInstanceOf(BizException.class);
        verify(llm, never()).stream(any(LlmRequest.class), any(LlmStreamSink.class));
        verify(repository, never()).appendMessage(anyLong(), anyLong(), anyString(), anyString());
    }

    private static DeskTicket ticket(long userId) {
        LocalDateTime now = LocalDateTime.now();
        return new DeskTicket(TICKET_ID, TENANT, TICKET_NO, userId, "无法登录", "AI_SERVING", now, now);
    }

    private static DeskMessage message(String role, String content) {
        return new DeskMessage(1L, TENANT, TICKET_ID, role, content, null, null, null, null,
                LocalDateTime.now());
    }

    private static BalanceView balance(String amount) {
        return new BalanceView(new SubjectRef(TENANT, 2L, USER), new BigDecimal(amount),
                BigDecimal.ZERO, "CNY");
    }

    private static final class RecordingListener implements DeskChatService.DeskChatListener {

        private final List<String> deltas = new ArrayList<>();
        private DeskChatService.DeskReply done;
        private Error error;

        @Override
        public void onDelta(String delta) {
            deltas.add(delta);
        }

        @Override
        public void onDone(DeskChatService.DeskReply reply) {
            done = reply;
        }

        @Override
        public void onError(String category, String message) {
            error = new Error(category, message);
        }

        private record Error(String category, String message) {
        }
    }
}
