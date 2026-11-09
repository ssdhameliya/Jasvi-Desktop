package org.example.server.whatsapp;

import java.util.List;

public final class WahaDtos {
    private WahaDtos() {}

    public record WahaSettingsDto(
        String endpoint,
        String apiKey,
        boolean enabled,
        Long defaultChannelId
    ) {}

    public record ChannelDto(
        Long id,
        String channelCode,
        String displayName,
        String phoneNumber,
        String sessionName,
        boolean isDefault,
        boolean isActive,
        String connectionStatus,
        String createdAt,
        String updatedAt
    ) {}

    public record ChannelSaveRequest(
        Long id,
        String channelCode,
        String displayName,
        String phoneNumber,
        String sessionName,
        Boolean isDefault,
        Boolean isActive
    ) {}

    public record DocumentSendRequest(
        String documentType,
        String documentNumber,
        Integer documentId,
        Long channelId,
        String recipientPhone,
        String customMessage,
        Long rowVersion
    ) {}

    public record TextSendRequest(
        String recipientPhone,
        String message,
        Long channelId
    ) {}

    public record SendResponse(
        boolean success,
        String message,
        String messageId,
        String channelCode,
        String recipientPhone
    ) {}

    public record QrResponse(
        String session,
        String qrImageBase64,
        String status,
        String message
    ) {}

    public record HealthResponse(
        boolean connected,
        String message,
        String serverVersion,
        List<SessionStatus> sessions
    ) {}

    public record DepartmentRoutingDto(
        Long defaultChannelId,
        Long salesChannelId,
        Long purchaseChannelId,
        Long quotationChannelId
    ) {}

    public record SessionStatus(
        String name,
        String status
    ) {}
}
