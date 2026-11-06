package dev.santiagosantofimio.minidb.catalog;

import dev.santiagosantofimio.minidb.StorageException;
import dev.santiagosantofimio.minidb.record.Column;
import dev.santiagosantofimio.minidb.record.DataType;
import dev.santiagosantofimio.minidb.record.DataType.BooleanType;
import dev.santiagosantofimio.minidb.record.DataType.DoubleType;
import dev.santiagosantofimio.minidb.record.DataType.IntType;
import dev.santiagosantofimio.minidb.record.DataType.VarcharType;
import dev.santiagosantofimio.minidb.record.Schema;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class CatalogRecords {

    private static final byte TABLE_RECORD = 1;

    private static final byte INT_TYPE = 1;
    private static final byte DOUBLE_TYPE = 2;
    private static final byte BOOLEAN_TYPE = 3;
    private static final byte VARCHAR_TYPE = 4;

    private CatalogRecords() {
    }

    static byte[] encode(Table table) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(TABLE_RECORD);
            writeString(out, table.name());
            out.writeInt(table.firstPageId());
            out.writeShort(table.schema().size());
            for (Column column : table.schema().columns()) {
                writeString(out, column.name());
                writeType(out, column.type());
                out.writeBoolean(column.nullable());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static Table decode(byte[] record) {
        ByteBuffer in = ByteBuffer.wrap(record);
        try {
            byte kind = in.get();
            if (kind != TABLE_RECORD) {
                throw new StorageException("damaged catalog: unknown record kind " + kind);
            }
            String name = readString(in);
            int firstPageId = in.getInt();
            int columnCount = Short.toUnsignedInt(in.getShort());
            List<Column> columns = new ArrayList<>(columnCount);
            for (int i = 0; i < columnCount; i++) {
                columns.add(new Column(readString(in), readType(in), in.get() != 0));
            }
            return new Table(name, new Schema(columns), firstPageId);
        } catch (BufferUnderflowException e) {
            throw new StorageException("damaged catalog: a table record ends too early", e);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(encoded.length);
        out.write(encoded);
    }

    private static String readString(ByteBuffer in) {
        byte[] encoded = new byte[Short.toUnsignedInt(in.getShort())];
        in.get(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static void writeType(DataOutputStream out, DataType type) throws IOException {
        switch (type) {
            case IntType integer -> out.writeByte(INT_TYPE);
            case DoubleType real -> out.writeByte(DOUBLE_TYPE);
            case BooleanType bool -> out.writeByte(BOOLEAN_TYPE);
            case VarcharType varchar -> {
                out.writeByte(VARCHAR_TYPE);
                out.writeShort(varchar.maxLength());
            }
        }
    }

    private static DataType readType(ByteBuffer in) {
        byte code = in.get();
        return switch (code) {
            case INT_TYPE -> DataType.INT;
            case DOUBLE_TYPE -> DataType.DOUBLE;
            case BOOLEAN_TYPE -> DataType.BOOLEAN;
            case VARCHAR_TYPE -> DataType.varchar(Short.toUnsignedInt(in.getShort()));
            default -> throw new StorageException("damaged catalog: unknown column type code " + code);
        };
    }
}
