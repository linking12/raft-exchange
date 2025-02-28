package com.binance.raftexchange.server.raft;

import com.binance.raftexchange.stubs.api.ApiCommand;
import com.binance.raftexchange.stubs.command.OrderCommand;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.Int32Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.ProtocolMessageEnum;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class SerializeHelper {
    private SerializeHelper() {}

    /**
     * [2-byte length] [类型字符串] [protobuf byte[]] ｜--------writeUTF--------｜｜---write---｜
     */
    public static byte[] serializeWithType(GeneratedMessageV3 message) throws IOException {
        String type = message.getClass().getSimpleName();
        byte[] byteArray = message.toByteArray();
        int capacity = 2 * Byte.BYTES + type.getBytes().length + byteArray.length;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(capacity);
        DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
        dataOutputStream.writeUTF(type);
        dataOutputStream.write(byteArray, 0, byteArray.length);
        return outputStream.toByteArray();
    }

    /**
     * [2-byte length] [类型字符串] [protobuf byte[]] ｜--------readUTF--------｜｜---readFully---｜
     */
    public static GeneratedMessageV3 deserializeWithType(byte[] data, int offset, int length) throws IOException {
        DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(data, offset, length));
        String type = dataInputStream.readUTF();
        byte[] payload = new byte[length - 2 * Byte.BYTES - type.getBytes().length];
        dataInputStream.readFully(payload);
        switch (type) {
            case "ApiCommand":
                return ApiCommand.parseFrom(payload);
            case "OrderCommand":
                return OrderCommand.parseFrom(payload);
            default:
                throw new IllegalArgumentException("Unknown command type: " + type);
        }
    }

    public static byte[] enumToBytesProto(ProtocolMessageEnum enumValue) {
        return Int32Value.newBuilder().setValue(enumValue.getNumber()).build().toByteArray();
    }

    public static <T extends ProtocolMessageEnum> T bytesToEnumProto(byte[] bytes, Class<T> enumClass)
        throws InvalidProtocolBufferException {
        int intValue = Int32Value.parseFrom(bytes).getValue();
        for (T value : enumClass.getEnumConstants()) {
            if (value.getNumber() == intValue) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown enum value: " + intValue);
    }
}
