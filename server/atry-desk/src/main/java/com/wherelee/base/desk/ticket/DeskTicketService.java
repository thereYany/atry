package com.wherelee.base.desk.ticket;

import com.wherelee.base.desk.config.DeskProperties;
import com.wherelee.base.modules.platform.common.BizException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 工单领域服务（M1）：建单、列表、详情。
 *
 * <p>AI 回复链路在下一步接入 {@code LlmOperations}（SSE + 计量），这里只做业务落库，
 * 避免垂直域复制内核的调用治理与计费逻辑。</p>
 */
@Service
public class DeskTicketService {

    /** 工单号：D + 时间戳 + 6 位随机，长度 21，满足 VARCHAR(32)。 */
    private static final DateTimeFormatter TICKET_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TICKET_NO_RANDOM_BOUND = 1_000_000;

    private final DeskTicketRepository repository;
    private final DeskProperties properties;

    public DeskTicketService(DeskTicketRepository repository, DeskProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public DeskTicket create(long tenantId, long userId, String subject, String firstMessage) {
        String ticketNo = nextTicketNo();
        long id = repository.createTicket(tenantId, ticketNo, userId, subject.trim());
        repository.appendMessage(tenantId, id, "USER", firstMessage.trim());
        return repository.findTicket(tenantId, ticketNo)
                .orElseThrow(() -> new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "工单创建后不可读取"));
    }

    public List<DeskTicket> list(long tenantId, long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, properties.historyLimit() * 10));
        return repository.listTickets(tenantId, userId, safeLimit);
    }

    public DeskTicketDetail detail(long tenantId, long userId, String ticketNo) {
        DeskTicket ticket = repository.findTicket(tenantId, ticketNo)
                .filter(found -> found.userId() == userId)
                .orElseThrow(() -> new BizException(HttpStatus.NOT_FOUND, "工单不存在"));
        List<DeskMessage> messages = repository.listMessages(tenantId, ticket.id(), 200);
        return new DeskTicketDetail(ticket, messages);
    }

    /** 工单详情（含消息时间线）。 */
    public record DeskTicketDetail(DeskTicket ticket, List<DeskMessage> messages) {
    }

    private String nextTicketNo() {
        return "D" + LocalDateTime.now().format(TICKET_TS)
                + String.format("%06d", RANDOM.nextInt(TICKET_NO_RANDOM_BOUND));
    }
}
