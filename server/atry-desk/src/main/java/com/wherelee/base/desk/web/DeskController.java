package com.wherelee.base.desk.web;

import com.wherelee.base.desk.ticket.DeskMessage;
import com.wherelee.base.desk.ticket.DeskTicket;
import com.wherelee.base.desk.ticket.DeskTicketService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客服工单入口。
 *
 * <p>tenantId / userId 只从 JWT 登录态读取（JwtAuthFilter 写入 request attribute），
 * 不接受请求体指定——垂直域同样不允许把越权入口交给客户端。</p>
 */
@RestController
@RequestMapping("/api/desk")
public class DeskController {

    private final DeskTicketService service;

    public DeskController(DeskTicketService service) {
        this.service = service;
    }

    /** 建单 + 首条问题。 */
    @PostMapping("/tickets")
    public DeskDtos.TicketDetailView create(@RequestAttribute("tenantId") long tenantId,
                                            @RequestAttribute("userId") long userId,
                                            @Valid @RequestBody DeskDtos.CreateTicketRequest request) {
        DeskTicket ticket = service.create(tenantId, userId, request.subject(), request.message());
        DeskTicketService.DeskTicketDetail detail = service.detail(tenantId, userId, ticket.ticketNo());
        return toView(detail);
    }

    /** 我的工单列表。 */
    @GetMapping("/tickets")
    public List<DeskDtos.TicketView> list(@RequestAttribute("tenantId") long tenantId,
                                          @RequestAttribute("userId") long userId,
                                          @RequestParam(defaultValue = "20") int limit) {
        return service.list(tenantId, userId, limit).stream().map(DeskController::toView).toList();
    }

    /** 工单详情（含消息）。 */
    @GetMapping("/tickets/{ticketNo}")
    public DeskDtos.TicketDetailView detail(@RequestAttribute("tenantId") long tenantId,
                                            @RequestAttribute("userId") long userId,
                                            @PathVariable String ticketNo) {
        return toView(service.detail(tenantId, userId, ticketNo));
    }

    private DeskDtos.TicketDetailView toView(DeskTicketService.DeskTicketDetail detail) {
        return new DeskDtos.TicketDetailView(toView(detail.ticket()),
                detail.messages().stream().map(DeskController::toView).toList());
    }

    private static DeskDtos.TicketView toView(DeskTicket ticket) {
        return new DeskDtos.TicketView(ticket.ticketNo(), ticket.subject(), ticket.status(),
                ticket.createdAt(), ticket.updatedAt());
    }

    private static DeskDtos.MessageView toView(DeskMessage message) {
        return new DeskDtos.MessageView(message.role(), message.content(), message.inputTokens(),
                message.outputTokens(), message.costMicros(), message.createdAt());
    }
}
