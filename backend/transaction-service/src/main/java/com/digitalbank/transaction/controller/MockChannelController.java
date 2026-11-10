package com.digitalbank.transaction.controller;

import com.digitalbank.common.api.ApiResponse;
import com.digitalbank.common.trace.TraceIds;
import com.digitalbank.transaction.channel.MockChannelService;
import com.digitalbank.transaction.channel.MockChannelService.ChannelRow;
import com.digitalbank.transaction.dto.TransactionDtos.ChannelMode;
import com.digitalbank.transaction.dto.TransactionDtos.ChannelRecordView;
import com.digitalbank.transaction.security.InternalAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟渠道演示接口（S3 内部，X-Internal-Token 保护，网关不路由）：
 * 切换结果模式以演示成功/失败/超时，以及查看渠道记录核对收敛过程。
 */
@RestController
@RequestMapping("/internal/v1/channel")
public class MockChannelController {
    private final MockChannelService channel;
    private final InternalAccess access;

    public MockChannelController(MockChannelService channel, InternalAccess access) {
        this.channel = channel;
        this.access = access;
    }

    @PostMapping("/mode")
    public ApiResponse<ChannelMode> mode(@RequestBody ChannelMode body, HttpServletRequest request) {
        access.require(request);
        channel.setMode(body.result());
        return ApiResponse.success(new ChannelMode(channel.mode()),
                (String) request.getAttribute(TraceIds.MDC_KEY));
    }

    @GetMapping("/deposits/{tradeNo}")
    public ApiResponse<ChannelRecordView> record(@PathVariable String tradeNo, HttpServletRequest request) {
        access.require(request);
        ChannelRow row = channel.find(tradeNo);
        if (row == null) {
            return ApiResponse.success(null, (String) request.getAttribute(TraceIds.MDC_KEY));
        }
        ChannelRecordView view = new ChannelRecordView(row.tradeNo(), row.channelRequestId(), row.accountId(),
                row.amount().toPlainString(), row.currency(), row.result(), row.evidenceId(), row.reasonCode(),
                row.observedAt() == null ? null : row.observedAt().toString(), row.reconcileAttempts(),
                row.nextCheckAt() == null ? null : row.nextCheckAt().toString(), row.manualReviewRequired());
        return ApiResponse.success(view, (String) request.getAttribute(TraceIds.MDC_KEY));
    }
}
