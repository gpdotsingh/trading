package com.trading.ibcfd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stateless parser for Saxo streaming binary frames.
 *
 * Frame structure per message:
 *   [8 bytes] message ID (64-bit LE)
 *   [2 bytes] version (skip)
 *   [1 byte]  refId length
 *   [N bytes] reference ID (ASCII)
 *   [1 byte]  payload format (0=JSON)
 *   [4 bytes] payload size (32-bit LE)
 *   [N bytes] payload
 */
@Slf4j
@Component
public class SaxoFrameParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record FrameMessage(long messageId, String referenceId, int payloadFormat, byte[] payload) {}

    /** Parse one complete reassembled frame into its constituent messages. */
    public List<FrameMessage> parse(byte[] frameBytes) {
        List<FrameMessage> messages = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(frameBytes).order(ByteOrder.LITTLE_ENDIAN);

        while (buf.remaining() >= 16) {
            long messageId  = buf.getLong();
            buf.getShort();
            int refIdSize   = buf.get() & 0xFF;
            if (buf.remaining() < refIdSize + 6) break;

            byte[] refIdBytes = new byte[refIdSize];
            buf.get(refIdBytes);
            String referenceId  = new String(refIdBytes, StandardCharsets.US_ASCII);
            int payloadFormat   = buf.get() & 0xFF;
            int payloadSize     = buf.getInt();
            if (buf.remaining() < payloadSize) break;

            byte[] payload = new byte[payloadSize];
            buf.get(payload);
            messages.add(new FrameMessage(messageId, referenceId, payloadFormat, payload));
        }
        return messages;
    }

    /** Deserialise a JSON payload into a list of price-update maps. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parseJsonPayload(byte[] payload) throws Exception {
        String json = new String(payload, StandardCharsets.UTF_8);
        return objectMapper.readValue(json, List.class);
    }
}
