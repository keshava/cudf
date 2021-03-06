/*
 *
 *  Copyright (c) 2019-2020, NVIDIA CORPORATION.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package ai.rapids.cudf;

import ai.rapids.cudf.HostColumnVector.Builder;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static ai.rapids.cudf.Aggregate.max;
import static ai.rapids.cudf.Table.TestBuilder;
import static ai.rapids.cudf.Table.count;
import static ai.rapids.cudf.Table.mean;
import static ai.rapids.cudf.Table.min;
import static ai.rapids.cudf.Table.sum;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TableTest extends CudfTestBase {
  private static final File TEST_PARQUET_FILE = new File("src/test/resources/acq.parquet");
  private static final File TEST_ORC_FILE = new File("src/test/resources/TestOrcFile.orc");
  private static final File TEST_ORC_TIMESTAMP_DATE_FILE = new File(
      "src/test/resources/timestamp-date-test.orc");

  private static final Schema CSV_DATA_BUFFER_SCHEMA = Schema.builder()
      .column(DType.INT32, "A")
      .column(DType.FLOAT64, "B")
      .column(DType.INT64, "C")
      .build();

  private static final byte[] CSV_DATA_BUFFER = ("A|B|C\n" +
      "'0'|'110.0'|'120'\n" +
      "1|111.0|121\n" +
      "2|112.0|122\n" +
      "3|113.0|123\n" +
      "4|114.0|124\n" +
      "5|115.0|125\n" +
      "6|116.0|126\n" +
      "7|NULL|127\n" +
      "8|118.2|128\n" +
      "9|119.8|129").getBytes(StandardCharsets.UTF_8);

  public static void assertColumnsAreEqual(ColumnVector expect, ColumnVector cv) {
    assertColumnsAreEqual(expect, cv, "unnamed");
  }

  public static void assertColumnsAreEqual(ColumnVector expected, ColumnVector cv, String colName) {
    assertPartialColumnsAreEqual(expected, 0, expected.getRowCount(), cv, colName, true);
  }

  public static void assertColumnsAreEqual(HostColumnVector expected, HostColumnVector cv, String colName) {
    assertPartialColumnsAreEqual(expected, 0, expected.getRowCount(), cv, colName, true);
  }

  public static void assertPartialColumnsAreEqual(ColumnVector expected, long rowOffset, long length,
                                                  ColumnVector cv, String colName, boolean enableNullCheck) {
    try (HostColumnVector hostExpected = expected.copyToHost();
         HostColumnVector hostcv = cv.copyToHost()) {
      assertPartialColumnsAreEqual(hostExpected, rowOffset, length, hostcv, colName, enableNullCheck);
    }
  }

  public static void assertPartialColumnsAreEqual(HostColumnVector expected, long rowOffset, long length,
                                                  HostColumnVector cv, String colName, boolean enableNullCheck) {
    assertEquals(expected.getType(), cv.getType(), "Type For Column " + colName);
    assertEquals(length, cv.getRowCount(), "Row Count For Column " + colName);
    if (enableNullCheck) {
      assertEquals(expected.getNullCount(), cv.getNullCount(), "Null Count For Column " + colName);
    } else {
      // TODO add in a proper check when null counts are supported by serializing a partitioned column
    }
    DType type = expected.getType();
    for (long expectedRow = rowOffset; expectedRow < (rowOffset + length); expectedRow++) {
      long tableRow = expectedRow - rowOffset;
      assertEquals(expected.isNull(expectedRow), cv.isNull(tableRow),
          "NULL for Column " + colName + " Row " + tableRow);
      if (!expected.isNull(expectedRow)) {
        switch (type) {
          case BOOL8: // fall through
          case INT8:
            assertEquals(expected.getByte(expectedRow), cv.getByte(tableRow),
                "Column " + colName + " Row " + tableRow);
            break;
          case INT16:
            assertEquals(expected.getShort(expectedRow), cv.getShort(tableRow),
                "Column " + colName + " Row " + tableRow);
            break;
          case INT32: // fall through
          case TIMESTAMP_DAYS:
            assertEquals(expected.getInt(expectedRow), cv.getInt(tableRow),
                "Column " + colName + " Row " + tableRow);
            break;
          case INT64: // fall through
          case TIMESTAMP_MICROSECONDS: // fall through
          case TIMESTAMP_MILLISECONDS: // fall through
          case TIMESTAMP_NANOSECONDS: // fall through
          case TIMESTAMP_SECONDS:
            assertEquals(expected.getLong(expectedRow), cv.getLong(tableRow),
                "Column " + colName + " Row " + tableRow);
            break;
          case FLOAT32:
            assertEquals(expected.getFloat(expectedRow), cv.getFloat(tableRow), 0.0001,
                "Column " + colName + " Row " + tableRow);
            break;
          case FLOAT64:
            assertEquals(expected.getDouble(expectedRow), cv.getDouble(tableRow), 0.0001,
                "Column " + colName + " Row " + tableRow);
            break;
          case STRING:
            assertArrayEquals(expected.getUTF8(expectedRow), cv.getUTF8(tableRow),
                "Column " + colName + " Row " + tableRow);
            break;
          default:
            throw new IllegalArgumentException(type + " is not supported yet");
        }
      }
    }
  }

  public static void assertPartialTablesAreEqual(Table expected, long rowOffset, long length, Table table, boolean enableNullCheck) {
    assertEquals(expected.getNumberOfColumns(), table.getNumberOfColumns());
    assertEquals(length, table.getRowCount(), "ROW COUNT");
    for (int col = 0; col < expected.getNumberOfColumns(); col++) {
      ColumnVector expect = expected.getColumn(col);
      ColumnVector cv = table.getColumn(col);
      String name = String.valueOf(col);
      if (rowOffset != 0 || length != expected.getRowCount()) {
        name = name + " PART " + rowOffset + "-" + (rowOffset + length - 1);
      }
      assertPartialColumnsAreEqual(expect, rowOffset, length, cv, name, enableNullCheck);
    }
  }

  public static void assertTablesAreEqual(Table expected, Table table) {
    assertPartialTablesAreEqual(expected, 0, expected.getRowCount(), table, true);
  }

  void assertTablesHaveSameValues(HashMap<Object, Integer>[] expectedTable, Table table) {
    assertEquals(expectedTable.length, table.getNumberOfColumns());
    int numCols = table.getNumberOfColumns();
    long numRows = table.getRowCount();
    for (int col = 0; col < numCols; col++) {
      for (long row = 0; row < numRows; row++) {
        try (HostColumnVector cv = table.getColumn(col).copyToHost()) {
          Object key = 0;
          if (cv.getType() == DType.INT32) {
            key = cv.getInt(row);
          } else {
            key = cv.getDouble(row);
          }
          assertTrue(expectedTable[col].containsKey(key));
          Integer count = expectedTable[col].get(key);
          if (count == 1) {
            expectedTable[col].remove(key);
          } else {
            expectedTable[col].put(key, count - 1);
          }
        }
      }
    }
    for (int i = 0 ; i < expectedTable.length ; i++) {
      assertTrue(expectedTable[i].isEmpty());
    }
  }

  public static void assertTableTypes(DType[] expectedTypes, Table t) {
    int len = t.getNumberOfColumns();
    assertEquals(expectedTypes.length, len);
    for (int i = 0; i < len; i++) {
      ColumnVector vec = t.getColumn(i);
      DType type = vec.getType();
      assertEquals(expectedTypes[i], type, "Types don't match at " + i);
    }
  }

  @Test
  void testOrderByAD() {
    try (Table table = new Table.TestBuilder()
        .column(5, 3, 3, 1, 1)
        .column(5, 3, 4, 1, 2)
        .column(1, 3, 5, 7, 9)
        .build();
         Table expected = new Table.TestBuilder()
             .column(1, 1, 3, 3, 5)
             .column(2, 1, 4, 3, 5)
             .column(9, 7, 5, 3, 1)
             .build();
         Table sortedTable = table.orderBy(Table.asc(0), Table.desc(1))) {
      assertTablesAreEqual(expected, sortedTable);
    }
  }

  @Test
  void testOrderByDD() {
    try (Table table = new Table.TestBuilder()
        .column(5, 3, 3, 1, 1)
        .column(5, 3, 4, 1, 2)
        .column(1, 3, 5, 7, 9)
        .build();
         Table expected = new Table.TestBuilder()
             .column(5, 3, 3, 1, 1)
             .column(5, 4, 3, 2, 1)
             .column(1, 5, 3, 9, 7)
             .build();
         Table sortedTable = table.orderBy(Table.desc(0), Table.desc(1))) {
      assertTablesAreEqual(expected, sortedTable);
    }
  }

  @Test
  void testOrderByWithNulls() {
    try (Table table = new Table.TestBuilder()
        .column(5, null, 3, 1, 1)
        .column(5, 3, 4, null, null)
        .column("4", "3", "2", "1", "0")
        .column(1, 3, 5, 7, 9)
        .build();
         Table expected = new Table.TestBuilder()
             .column(1, 1, 3, 5, null)
             .column(null, null, 4, 5, 3)
             .column("1", "0", "2", "4", "3")
             .column(7, 9, 5, 1, 3)
             .build();
         Table sortedTable = table.orderBy(Table.asc(0), Table.desc(1))) {
      assertTablesAreEqual(expected, sortedTable);
    }
  }

  @Test
  void testOrderByWithNullsAndStrings() {
    try (Table table = new Table.TestBuilder()
        .column("4", "3", "2", "1", "0")
        .column(5, null, 3, 1, 1)
        .column(5, 3, 4, null, null)
        .column(1, 3, 5, 7, 9)
        .build();
         Table expected = new Table.TestBuilder()
             .column("0", "1", "2", "3", "4")
             .column(1, 1, 3, null, 5)
             .column(null, null, 4, 3, 5)
             .column(9, 7, 5, 3, 1)
             .build();
         Table sortedTable = table.orderBy(Table.asc(0))) {
      assertTablesAreEqual(expected, sortedTable);
    }
  }

  @Test
  void testTableCreationIncreasesRefCount() {
    //tests the Table increases the refcount on column vectors
    assertThrows(IllegalStateException.class, () -> {
      try (ColumnVector v1 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5));
           ColumnVector v2 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5))) {
        assertDoesNotThrow(() -> {
          try (Table t = new Table(new ColumnVector[]{v1, v2})) {
            v1.close();
            v2.close();
          }
        });
      }
    });
  }

  @Test
  void testGetRows() {
    try (ColumnVector v1 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5));
         ColumnVector v2 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5));
         Table t = new Table(new ColumnVector[]{v1, v2})) {
      assertEquals(5, t.getRowCount());
    }
  }

  @Test
  void testSettingNullVectors() {
    ColumnVector[] columnVectors = null;
    assertThrows(AssertionError.class, () -> new Table(columnVectors));
  }

  @Test
  void testAllRowsSize() {
    try (ColumnVector v1 = ColumnVector.build(DType.INT32, 4, Range.appendInts(4));
         ColumnVector v2 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5))) {
      assertThrows(AssertionError.class, () -> {
        try (Table t = new Table(new ColumnVector[]{v1, v2})) {
        }
      });
    }
  }

  @Test
  void testGetNumberOfColumns() {
    try (ColumnVector v1 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5));
         ColumnVector v2 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5));
         Table t = new Table(new ColumnVector[]{v1, v2})) {
      assertEquals(2, t.getNumberOfColumns());
    }
  }

  @Test
  void testReadCSVPrune() {
    Schema schema = Schema.builder()
        .column(DType.INT32, "A")
        .column(DType.FLOAT64, "B")
        .column(DType.INT64, "C")
        .build();
    CSVOptions opts = CSVOptions.builder()
        .includeColumn("A")
        .includeColumn("B")
        .build();
    try (Table expected = new Table.TestBuilder()
        .column(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .column(110.0, 111.0, 112.0, 113.0, 114.0, 115.0, 116.0, 117.0, 118.2, 119.8)
        .build();
         Table table = Table.readCSV(schema, opts, new File("./src/test/resources/simple.csv"))) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadCSVBufferInferred() {
    CSVOptions opts = CSVOptions.builder()
        .includeColumn("A")
        .includeColumn("B")
        .hasHeader()
        .withComment('#')
        .build();
    byte[] data = ("A,B,C\n" +
        "0,110.0,120'\n" +
        "#0.5,1.0,200\n" +
        "1,111.0,121\n" +
        "2,112.0,122\n" +
        "3,113.0,123\n" +
        "4,114.0,124\n" +
        "5,115.0,125\n" +
        "6,116.0,126\n" +
        "7,117.0,127\n" +
        "8,118.2,128\n" +
        "9,119.8,129").getBytes(StandardCharsets.UTF_8);
    try (Table expected = new Table.TestBuilder()
        .column(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)
        .column(110.0, 111.0, 112.0, 113.0, 114.0, 115.0, 116.0, 117.0, 118.2, 119.8)
        .build();
         Table table = Table.readCSV(Schema.INFERRED, opts, data)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadCSVBuffer() {
    CSVOptions opts = CSVOptions.builder()
        .includeColumn("A")
        .includeColumn("B")
        .hasHeader()
        .withDelim('|')
        .withQuote('\'')
        .withNullValue("NULL")
        .build();
    try (Table expected = new Table.TestBuilder()
        .column(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .column(110.0, 111.0, 112.0, 113.0, 114.0, 115.0, 116.0, null, 118.2, 119.8)
        .build();
         Table table = Table.readCSV(TableTest.CSV_DATA_BUFFER_SCHEMA, opts,
             TableTest.CSV_DATA_BUFFER)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadCSVWithOffset() {
    CSVOptions opts = CSVOptions.builder()
        .includeColumn("A")
        .includeColumn("B")
        .hasHeader(false)
        .withDelim('|')
        .withNullValue("NULL")
        .build();
    int bytesToIgnore = 24;
    try (Table expected = new Table.TestBuilder()
        .column(1, 2, 3, 4, 5, 6, 7, 8, 9)
        .column(111.0, 112.0, 113.0, 114.0, 115.0, 116.0, null, 118.2, 119.8)
        .build();
         Table table = Table.readCSV(TableTest.CSV_DATA_BUFFER_SCHEMA, opts,
             TableTest.CSV_DATA_BUFFER, bytesToIgnore, CSV_DATA_BUFFER.length - bytesToIgnore)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadCSVOtherTypes() {
    final byte[] CSV_DATA_WITH_TYPES = ("A,B,C,D\n" +
        "0,true,120,\"zero\"\n" +
        "1,True,121,\"one\"\n" +
        "2,false,122,\"two\"\n" +
        "3,false,123,\"three\"\n" +
        "4,TRUE,124,\"four\"\n" +
        "5,true,125,\"five\"\n" +
        "6,true,126,\"six\"\n" +
        "7,NULL,127,NULL\n" +
        "8,false,128,\"eight\"\n" +
        "9,false,129,\"nine\uD80C\uDC3F\"").getBytes(StandardCharsets.UTF_8);

    final Schema CSV_DATA_WITH_TYPES_SCHEMA = Schema.builder()
        .column(DType.INT32, "A")
        .column(DType.BOOL8, "B")
        .column(DType.INT64, "C")
        .column(DType.STRING, "D")
        .build();

    CSVOptions opts = CSVOptions.builder()
        .includeColumn("A", "B", "D")
        .hasHeader(true)
        .withNullValue("NULL")
        .withQuote('"')
        .withTrueValue("true", "True", "TRUE")
        .withFalseValue("false")
        .build();
    try (Table expected = new Table.TestBuilder()
        .column(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .column(true, true, false, false, true, true, true, null, false, false)
        .column("zero", "one", "two", "three", "four", "five", "six", null, "eight", "nine\uD80C\uDC3F")
        .build();
         Table table = Table.readCSV(CSV_DATA_WITH_TYPES_SCHEMA, opts, CSV_DATA_WITH_TYPES)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadCSV() {
    Schema schema = Schema.builder()
        .column(DType.INT32, "A")
        .column(DType.FLOAT64, "B")
        .column(DType.INT64, "C")
        .column(DType.STRING, "D")
        .build();
    try (Table expected = new Table.TestBuilder()
        .column(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .column(110.0, 111.0, 112.0, 113.0, 114.0, 115.0, 116.0, 117.0, 118.2, 119.8)
        .column(120L, 121L, 122L, 123L, 124L, 125L, 126L, 127L, 128L, 129L)
        .column("one", "two", "three", "four", "five", "six", "seven\ud801\uddb8", "eight\uBF68", "nine\u03E8", "ten")
        .build();
         Table table = Table.readCSV(schema, new File("./src/test/resources/simple.csv"))) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadParquet() {
    ParquetOptions opts = ParquetOptions.builder()
        .includeColumn("loan_id")
        .includeColumn("zip")
        .includeColumn("num_units")
        .build();
    try (Table table = Table.readParquet(opts, TEST_PARQUET_FILE)) {
      long rows = table.getRowCount();
      assertEquals(1000, rows);
      assertTableTypes(new DType[]{DType.INT64, DType.INT32, DType.INT32}, table);
    }
  }

  @Test
  void testReadParquetBuffer() throws IOException {
    ParquetOptions opts = ParquetOptions.builder()
        .includeColumn("loan_id")
        .includeColumn("coborrow_credit_score")
        .includeColumn("borrower_credit_score")
        .build();

    byte[] buffer = new byte[(int) TEST_PARQUET_FILE.length() + 1024];
    int bufferLen = 0;
    try (FileInputStream in = new FileInputStream(TEST_PARQUET_FILE)) {
      bufferLen = in.read(buffer);
    }
    try (Table table = Table.readParquet(opts, buffer, 0, bufferLen)) {
      long rows = table.getRowCount();
      assertEquals(1000, rows);
      assertTableTypes(new DType[]{DType.INT64, DType.FLOAT64, DType.FLOAT64}, table);
    }
  }

  @Test
  void testReadParquetFull() {
    try (Table table = Table.readParquet(TEST_PARQUET_FILE)) {
      long rows = table.getRowCount();
      assertEquals(1000, rows);

      DType[] expectedTypes = new DType[]{
          DType.INT64, // loan_id
          DType.INT32, // orig_channel
          DType.FLOAT64, // orig_interest_rate
          DType.INT32, // orig_upb
          DType.INT32, // orig_loan_term
          DType.TIMESTAMP_DAYS, // orig_date
          DType.TIMESTAMP_DAYS, // first_pay_date
          DType.FLOAT64, // orig_ltv
          DType.FLOAT64, // orig_cltv
          DType.FLOAT64, // num_borrowers
          DType.FLOAT64, // dti
          DType.FLOAT64, // borrower_credit_score
          DType.INT32, // first_home_buyer
          DType.INT32, // loan_purpose
          DType.INT32, // property_type
          DType.INT32, // num_units
          DType.INT32, // occupancy_status
          DType.INT32, // property_state
          DType.INT32, // zip
          DType.FLOAT64, // mortgage_insurance_percent
          DType.INT32, // product_type
          DType.FLOAT64, // coborrow_credit_score
          DType.FLOAT64, // mortgage_insurance_type
          DType.INT32, // relocation_mortgage_indicator
          DType.INT32, // quarter
          DType.INT32 // seller_id
      };

      assertTableTypes(expectedTypes, table);
    }
  }

  @Test
  void testReadORC() {
    ORCOptions opts = ORCOptions.builder()
        .includeColumn("string1")
        .includeColumn("float1")
        .includeColumn("int1")
        .build();
    try (Table expected = new Table.TestBuilder()
        .column("hi","bye")
        .column(1.0f,2.0f)
        .column(65536,65536)
        .build();
         Table table = Table.readORC(opts, TEST_ORC_FILE)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadORCBuffer() throws IOException {
    ORCOptions opts = ORCOptions.builder()
        .includeColumn("string1")
        .includeColumn("float1")
        .includeColumn("int1")
        .build();

    int bufferLen = 0;
    byte[] buffer = Files.readAllBytes(TEST_ORC_FILE.toPath());
    bufferLen = buffer.length;
    try (Table expected = new Table.TestBuilder()
        .column("hi","bye")
        .column(1.0f,2.0f)
        .column(65536,65536)
        .build();
         Table table = Table.readORC(opts, buffer, 0, bufferLen)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadORCFull() {
    try (Table expected = new Table.TestBuilder()
        .column(false, true)
        .column((byte)1, (byte)100)
        .column((short)1024, (short)2048)
        .column(65536, 65536)
        .column(9223372036854775807L,9223372036854775807L)
        .column(1.0f, 2.0f)
        .column(-15.0, -5.0)
        .column("hi", "bye")
        .build();
         Table table = Table.readORC(TEST_ORC_FILE)) {
      assertTablesAreEqual(expected,  table);
    }
  }

  @Test
  void testReadORCNumPyTypes() {
    // by default ORC will promote TIMESTAMP_DAYS to TIMESTAMP_MILLISECONDS
    DType found;
    try (Table table = Table.readORC(TEST_ORC_TIMESTAMP_DATE_FILE)) {
      assertEquals(2, table.getNumberOfColumns());
      found = table.getColumn(0).getType();
      assertTrue(found.isTimestamp());
      assertEquals(DType.TIMESTAMP_MILLISECONDS, table.getColumn(1).getType());
    }

    // specifying no NumPy types should load them as TIMESTAMP_DAYS
    ORCOptions opts = ORCOptions.builder().withNumPyTypes(false).build();
    try (Table table = Table.readORC(opts, TEST_ORC_TIMESTAMP_DATE_FILE)) {
      assertEquals(2, table.getNumberOfColumns());
      assertEquals(found, table.getColumn(0).getType());
      assertEquals(DType.TIMESTAMP_DAYS, table.getColumn(1).getType());
    }
  }

  @Test
  void testReadORCTimeUnit() {
    // specifying no NumPy types should load them as TIMESTAMP_DAYS.
    // specifying a specific type will return the result in that unit
    ORCOptions opts = ORCOptions.builder()
        .withNumPyTypes(false)
        .withTimeUnit(DType.TIMESTAMP_SECONDS)
        .build();
    try (Table table = Table.readORC(opts, TEST_ORC_TIMESTAMP_DATE_FILE)) {
      assertEquals(2, table.getNumberOfColumns());
      assertEquals(DType.TIMESTAMP_SECONDS, table.getColumn(0).getType());
      assertEquals(DType.TIMESTAMP_DAYS, table.getColumn(1).getType());
    }
  }

  @Test
  void testLeftJoinWithNulls() {
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(  6,   5,   9,   8,  10,  32)
             .column(201, 202, 203, 204, 205, 206)
             .build();
         Table expected = new Table.TestBuilder()
             .column(   2,    3,   9,    0,    1,    7,    4,   6,   5,   8) // common
             .column( 100,  101, 102,  103,  104,  105,  106, 107, 108, 109) // left
             .column(null, null, 203, null, null, null, null, 201, 202, 204) // right
             .build();
         Table joinedTable = leftTable.onColumns(0).leftJoin(rightTable.onColumns(0));
         Table orderedJoinedTable = joinedTable.orderBy(Table.asc(1, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testLeftJoin() {
    try (Table leftTable = new Table.TestBuilder()
        .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317)
        .column( 10,  11,  12,  13,  14,  15,  16,  17,  18,  19)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(306, 301, 360, 109, 335, 254, 317, 361, 251, 326)
             .column( 20,  21,  22,  23,  24,  25,  26,  27,  28,  29)
             .build();
         Table joinedTable = leftTable.onColumns(0).leftJoin(rightTable.onColumns(new int[]{0}));
         Table orderedJoinedTable = joinedTable.orderBy(Table.asc(1, true));
         Table expected = new Table.TestBuilder()
             .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317) // common
             .column( 10,  11,  12,  13,  14,  15,  16,  17,  18,  19) // left
             .column( 22,  29,  25,  20,  23,  27,  28,  24,  21,  26) // right
             .build()) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testInnerJoinWithNonCommonKeys() {
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(  6,   5,   9,   8,  10,  32)
             .column(200, 201, 202, 203, 204, 205)
             .build();
         Table expected = new Table.TestBuilder()
             .column(  9,   6,   5,   8) // common
             .column(102, 107, 108, 109) // left
             .column(202, 200, 201, 203) // right
             .build();
         Table joinedTable = leftTable.onColumns(0).innerJoin(rightTable.onColumns(0));
         Table orderedJoinedTable = joinedTable.orderBy(Table.asc(1, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testInnerJoinWithOnlyCommonKeys() {
    try (Table leftTable = new Table.TestBuilder()
        .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(306, 301, 360, 109, 335, 254, 317, 361, 251, 326)
             .column(200, 201, 202, 203, 204, 205, 206, 207, 208, 209)
             .build();
         Table joinedTable = leftTable.onColumns(0).innerJoin(rightTable.onColumns(new int[]{0}));
         Table orderedJoinedTable = joinedTable.orderBy(Table.asc(1, true));
         Table expected = new Table.TestBuilder()
             .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317) // common
             .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109) // left
             .column(202, 209, 205, 200, 203, 207, 208, 204, 201, 206) // right
             .build()) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testLeftSemiJoin() {
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(  6,   5,   9,   8,  10,  32)
             .column(201, 202, 203, 204, 205, 206)
             .build();
         Table expected = new Table.TestBuilder()
             .column(  9,   6,   5,   8)
             .column(102, 107, 108, 109)
             .build();
         Table joinedTable = leftTable.onColumns(0).leftSemiJoin(rightTable.onColumns(0));
         Table orderedJoinedTable = joinedTable.orderBy(Table.asc(1, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testLeftSemiJoinWithNulls() {
    try (Table leftTable = new Table.TestBuilder()
        .column( 360,  326, null,  306, null,  254,  251,  361,  301,  317)
        .column(  10,   11, null,   13,   14, null,   16,   17,   18,   19)
        .column("20", "29", "22", "23", "24", "25", "26", "27", "28", "29")
        .build();
         Table rightTable = new Table.TestBuilder()
             .column( 306,  301,  360,  109,  335,  254,  317,  361,  251,  326)
             .column("20", "21", "22", "23", "24", "25", "26", "27", "28", "29")
             .build();
         Table joinedTable = leftTable.onColumns(0, 2).leftSemiJoin(rightTable.onColumns(0, 1));
         Table orderedJoinedTable = joinedTable.orderBy(Table.asc(0, true));
         Table expected = new Table.TestBuilder()
             .column(254,   326,   361)
             .column(null,   11,    17)
             .column("25", "29",  "27")
             .build()) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testLeftAntiJoin() {
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(  6,   5,   9,   8,  10,  32)
             .column(201, 202, 203, 204, 205, 206)
             .build();
         Table expected = new Table.TestBuilder()
             .column(  2,   3,   0,   1,   7,   4)
             .column(100, 101, 103, 104, 105, 106)
             .build();
         Table joinedTable = leftTable.onColumns(0).leftAntiJoin(rightTable.onColumns(0));
         Table orderedJoinedTable = joinedTable.orderBy(Table.asc(1, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testLeftAntiJoinWithNulls() {
    try (Table leftTable = new Table.TestBuilder()
        .column( 360,  326, null,  306, null,  254,  251,  361,  301,  317)
        .column(  10,   11, null,   13,   14, null,   16,   17,   18,   19)
        .column("20", "21", "22", "23", "24", "25", "26", "27", "28", "29")
        .build();
         Table rightTable = new Table.TestBuilder()
             .column( 306,  301,  360,  109,  335,  254,  317,  361,  251,  326)
             .column("20", "21", "22", "23", "24", "25", "26", "27", "28", "29")
             .build();
         Table joinedTable = leftTable.onColumns(0, 2).leftAntiJoin(rightTable.onColumns(0, 1));
         Table orderedJoinedTable = joinedTable.orderBy(Table.asc(2, true));
         Table expected = new Table.TestBuilder()
             .column( 360,  326, null,  306, null,  251,  301,  317)
             .column(  10,   11, null,   13,   14,   16,   18,   19)
             .column("20", "21", "22", "23", "24", "26", "28", "29")
             .build()) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testBoundsNulls() {
    boolean[] descFlags = new boolean[1];
    try (Table table = new TestBuilder()
            .column(null, 20, 20, 20, 30)
            .build();
        Table values = new TestBuilder()
            .column(15)
            .build();
         ColumnVector expected = ColumnVector.fromBoxedInts(1)) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsValuesSizeBigger() {
    boolean[] descFlags = new boolean[2];
    try(Table table = new TestBuilder()
            .column(90, 100, 120, 130, 135)
            .column(.5, .5, .5, .7, .7)
            .build();
        Table values = new TestBuilder()
            .column(120)
            .column(.3)
            .column(.7)
            .build()) {
      assertThrows(CudfException.class, () -> getBoundsCv(descFlags, true, table, values));
      assertThrows(CudfException.class, () ->  getBoundsCv(descFlags, false, table, values));
    }
  }

  @Test
  void testBoundsInputSizeBigger() {
    boolean[] descFlags = new boolean[3];
    try(Table table = new TestBuilder()
            .column(90, 100, 120, 130, 135)
            .column(.5, .5, .5, .7, .7)
            .column(90, 100, 120, 130, 135)
            .build();
        Table values = new TestBuilder()
            .column(120)
            .column(.3)
            .build()) {
      assertThrows(CudfException.class, () -> getBoundsCv(descFlags, true, table, values));
      assertThrows(CudfException.class, () -> getBoundsCv(descFlags, false, table, values));
    }
  }

  @Test
  void testBoundsMultiCol() {
    boolean[] descFlags = new boolean[4];
    try (Table table = new TestBuilder()
            .column(10, 20, 20, 20, 20)
            .column(5.0, .5, .5, .7, .7)
            .column("1","2","3","4","4")
            .column(90, 77, 78, 61, 61)
            .build();
        Table values = new TestBuilder()
            .column(20)
            .column(0.7)
            .column("4")
            .column(61)
            .build()) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(5)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(3)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsFloatsMultiVal() {
    boolean[] descFlags = new boolean[1];
    try (Table table = new TestBuilder()
            .column(10.0, 20.6, 20.7)
            .build();
        Table values = new TestBuilder()
            .column(20.3, 20.8)
            .build();
         ColumnVector expected = ColumnVector.fromBoxedInts(1, 3)) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsFloatsSingleCol() {
    boolean[] descFlags = {false};
    try(Table table = new TestBuilder()
            .column(10.0, 20.6, 20.7)
            .build();
        Table values = new TestBuilder()
            .column(20.6)
            .build()) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(2)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(1)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsFloatsSingleColDesc() {
    boolean[] descFlags = new boolean[] {true};
    try(Table table = new TestBuilder()
        .column(20.7, 20.6, 10.0)
        .build();
        Table values = new TestBuilder()
            .column(20.6)
            .build()) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(2)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(1)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsIntsSingleCol() {
    boolean[] descFlags = new boolean[1];
    try(Table table = new TestBuilder()
            .column(10, 20, 20, 20, 20)
            .build();
        Table values = new TestBuilder()
            .column(20)
            .build()) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(5)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(1)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsIntsSingleColDesc() {
    boolean[] descFlags = new boolean[]{true};
    try (Table table = new TestBuilder()
        .column(20, 20, 20, 20, 10)
        .build();
         Table values = new TestBuilder()
             .column(5)
             .build();
         ColumnVector expected = ColumnVector.fromBoxedInts(5)) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsString() {
    boolean[] descFlags = new boolean[1];
    try (ColumnVector cIn = ColumnVector.build(DType.STRING, 4, (b) -> {
           for (int i = 0; i < 4; i++) {
             b.appendUTF8String(String.valueOf(i).getBytes());
           }
        });
        Table table = new Table(cIn);
        ColumnVector cVal = ColumnVector.fromStrings("0");
        Table values = new Table(cVal)) {
      try (ColumnVector cv = getBoundsCv(descFlags, true, table, values);
           ColumnVector expected = ColumnVector.fromInts(1)) {
        assertColumnsAreEqual(expected, cv);
      }
      try (ColumnVector cv = getBoundsCv(descFlags, false, table, values);
           ColumnVector expected = ColumnVector.fromInts(0)) {
        assertColumnsAreEqual(expected, cv);
      }
    }
  }

  @Test
  void testBoundsEmptyValues() {
    boolean[] descFlags = new boolean[1];
    try (ColumnVector cv = ColumnVector.fromBoxedLongs();
         Table table = new TestBuilder()
             .column(10, 20, 20, 20, 20)
             .build();
         Table values = new Table(cv)) {
      assertThrows(AssertionError.class,
          () -> getBoundsCv(descFlags, true, table, values).close());
      assertThrows(AssertionError.class,
          () -> getBoundsCv(descFlags, false, table, values).close());
    }
  }

  @Test
  void testBoundsEmptyInput() {
    boolean[] descFlags = new boolean[1];
    try (ColumnVector cv =  ColumnVector.fromBoxedLongs();
         Table table = new Table(cv);
         Table values = new TestBuilder()
             .column(20)
             .build()) {
      assertThrows(AssertionError.class,
          () -> getBoundsCv(descFlags, true, table, values).close());
      assertThrows(AssertionError.class,
          () -> getBoundsCv(descFlags, false, table, values).close());
    }
  }

  private ColumnVector getBoundsCv(boolean[] descFlags, boolean isUpperBound,
      Table table, Table values) {
    boolean[] nullsAreSmallest = new boolean[descFlags.length];
    Arrays.fill(nullsAreSmallest, true);
    return isUpperBound ?
        table.upperBound(nullsAreSmallest, values, descFlags) :
        table.lowerBound(nullsAreSmallest, values, descFlags);
  }

  @Test
  void testConcatNoNulls() {
    try (Table t1 = new Table.TestBuilder()
        .column(1, 2, 3)
        .column("1", "2", "3")
        .timestampMicrosecondsColumn(1L, 2L, 3L)
        .column(11.0, 12.0, 13.0).build();
         Table t2 = new Table.TestBuilder()
             .column(4, 5)
             .column("4", "3")
             .timestampMicrosecondsColumn(4L, 3L)
             .column(14.0, 15.0).build();
         Table t3 = new Table.TestBuilder()
             .column(6, 7, 8, 9)
             .column("4", "1", "2", "2")
             .timestampMicrosecondsColumn(4L, 1L, 2L, 2L)
             .column(16.0, 17.0, 18.0, 19.0).build();
         Table concat = Table.concatenate(t1, t2, t3);
         Table expected = new Table.TestBuilder()
             .column(1, 2, 3, 4, 5, 6, 7, 8, 9)
             .column("1", "2", "3", "4", "3", "4", "1", "2", "2")
             .timestampMicrosecondsColumn(1L, 2L, 3L, 4L, 3L, 4L, 1L, 2L, 2L)
             .column(11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0).build()) {
      assertTablesAreEqual(expected, concat);
    }
  }

  @Test
  void testConcatWithNulls() {
    try (Table t1 = new Table.TestBuilder()
        .column(1, null, 3)
        .column(11.0, 12.0, 13.0).build();
         Table t2 = new Table.TestBuilder()
             .column(4, null)
             .column(14.0, 15.0).build();
         Table t3 = new Table.TestBuilder()
             .column(6, 7, 8, 9)
             .column(null, null, 18.0, 19.0).build();
         Table concat = Table.concatenate(t1, t2, t3);
         Table expected = new Table.TestBuilder()
             .column(1, null, 3, 4, null, 6, 7, 8, 9)
             .column(11.0, 12.0, 13.0, 14.0, 15.0, null, null, 18.0, 19.0).build()) {
      assertTablesAreEqual(expected, concat);
    }
  }

  @Test
  void testContiguousSplit() {
    ContiguousTable[] splits = null;
    try (Table t1 = new Table.TestBuilder()
        .column(10, 12, 14, 16, 18, 20, 22, 24, null, 28)
        .column(50, 52, 54, 56, 58, 60, 62, 64, 66, null)
        .build()) {
      splits = t1.contiguousSplit(2, 5, 9);
      assertEquals(4, splits.length);
      assertEquals(2, splits[0].getTable().getRowCount());
      assertEquals(3, splits[1].getTable().getRowCount());
      assertEquals(4, splits[2].getTable().getRowCount());
      assertEquals(1, splits[3].getTable().getRowCount());
    } finally {
      if (splits != null) {
        for (int i = 0; i < splits.length; i++) {
          splits[i].close();
        }
      }
    }
  }

  @Disabled
  @Test
  void testContiguousSplitWithStrings() {
    ContiguousTable[] splits = null;
    try (Table t1 = new Table.TestBuilder()
        .column(10, 12, 14, 16, 18, 20, 22, 24, null, 28)
        .column(50, 52, 54, 56, 58, 60, 62, 64, 66, null)
        .column("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")
        .build()) {
      splits = t1.contiguousSplit(2, 5, 9);
      assertEquals(4, splits.length);
      assertEquals(2, splits[0].getTable().getRowCount());
      assertEquals(3, splits[1].getTable().getRowCount());
      assertEquals(4, splits[2].getTable().getRowCount());
      assertEquals(1, splits[3].getTable().getRowCount());
    } finally {
      if (splits != null) {
        for (int i = 0; i < splits.length; i++) {
          splits[i].close();
        }
      }
    }
  }

  @Test
  void testPartStability() {
    final int PARTS = 5;
    int expectedPart = -1;
    try (Table start = new Table.TestBuilder().column(0).build();
         PartitionedTable out = start.onColumns(0).partition(PARTS)) {
      // Lets figure out what partitions this is a part of.
      int[] parts = out.getPartitions();
      for (int i = 0; i < parts.length; i++) {
        if (parts[i] > 0) {
          expectedPart = i;
        }
      }
    }
    final int COUNT = 20;
    for (int numEntries = 1; numEntries < COUNT; numEntries++) {
      try (ColumnVector data = ColumnVector.build(DType.INT32, numEntries, Range.appendInts(0, numEntries));
           Table t = new Table(data);
           PartitionedTable out = t.onColumns(0).partition(PARTS);
           HostColumnVector tmp = out.getColumn(0).copyToHost()) {
        // Now we need to get the range out for the partition we expect
        int[] parts = out.getPartitions();
        int start = expectedPart == 0 ? 0 : parts[expectedPart - 1];
        int end = parts[expectedPart];
        boolean found = false;
        for (int i = start; i < end; i++) {
          if (tmp.getInt(i) == 0) {
            found = true;
            break;
          }
        }
        assertTrue(found);
      }
    }
  }

  @Test
  void testPartition() {
    final int count = 1024 * 1024;
    try (ColumnVector aIn = ColumnVector.build(DType.INT64, count, Range.appendLongs(count));
         ColumnVector bIn = ColumnVector.build(DType.INT32, count, (b) -> {
           for (int i = 0; i < count; i++) {
             b.append(i / 2);
           }
         });
         ColumnVector cIn = ColumnVector.build(DType.STRING, count, (b) -> {
           for (int i = 0; i < count; i++) {
             b.appendUTF8String(String.valueOf(i).getBytes());
           }
         })) {

      HashSet<Long> expected = new HashSet<>();
      for (long i = 0; i < count; i++) {
        expected.add(i);
      }
      try (Table input = new Table(new ColumnVector[]{aIn, bIn, cIn});
           PartitionedTable output = input.onColumns(0).partition(5)) {
        int[] parts = output.getPartitions();
        assertEquals(5, parts.length);
        assertEquals(0, parts[0]);
        int previous = 0;
        long rows = 0;
        for (int i = 1; i < parts.length; i++) {
          assertTrue(parts[i] >= previous);
          rows += parts[i] - previous;
          previous = parts[i];
        }
        assertTrue(rows <= count);
        try (HostColumnVector aOut = output.getColumn(0).copyToHost();
             HostColumnVector bOut = output.getColumn(1).copyToHost();
             HostColumnVector cOut = output.getColumn(2).copyToHost()) {

          for (int i = 0; i < count; i++) {
            long fromA = aOut.getLong(i);
            long fromB = bOut.getInt(i);
            String fromC = cOut.getJavaString(i);
            assertTrue(expected.remove(fromA));
            assertEquals(fromA / 2, fromB);
            assertEquals(String.valueOf(fromA), fromC, "At Index " + i);
          }
          assertTrue(expected.isEmpty());
        }
      }
    }
  }

  @Test
  void testSerializationRoundTripEmpty() throws IOException {
    try (ColumnVector emptyInt = ColumnVector.fromInts();
         ColumnVector emptyDouble = ColumnVector.fromDoubles();
         ColumnVector emptyString = ColumnVector.fromStrings();
         Table t = new Table(emptyInt, emptyInt, emptyDouble, emptyString)) {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      JCudfSerialization.writeToStream(t, bout, 0, 0);
      ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
      DataInputStream din = new DataInputStream(bin);
      try (JCudfSerialization.TableAndRowCountPair result = JCudfSerialization.readTableFrom(din)) {
        assertTablesAreEqual(t, result.getTable());
      }
    }
  }

  @Test
  void testSerializationZeroColumns() throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    JCudfSerialization.writeRowsToStream(bout, 10);
    ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
    try (JCudfSerialization.TableAndRowCountPair result = JCudfSerialization.readTableFrom(bin)) {
      assertNull(result.getTable());
      assertEquals(10, result.getNumRows());
    }
  }

  @Test
  void testSerializationZeroColsZeroRows() throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    JCudfSerialization.writeRowsToStream(bout, 0);
    ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
    try (JCudfSerialization.TableAndRowCountPair result = JCudfSerialization.readTableFrom(bin)) {
      assertNull(result.getTable());
      assertEquals(0, result.getNumRows());
    }
  }

  @Test
  void testSerializationRoundTripConcatOnHostEmpty() throws IOException {
    try (ColumnVector emptyInt = ColumnVector.fromInts();
         ColumnVector emptyDouble = ColumnVector.fromDoubles();
         ColumnVector emptyString = ColumnVector.fromStrings();
         Table t = new Table(emptyInt, emptyInt, emptyDouble, emptyString)) {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      JCudfSerialization.writeToStream(t, bout, 0, 0);
      JCudfSerialization.writeToStream(t, bout, 0, 0);
      ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
      DataInputStream din = new DataInputStream(bin);

      ArrayList<JCudfSerialization.SerializedTableHeader> headers = new ArrayList<>();
      List<HostMemoryBuffer> buffers = new ArrayList<>();
      try {
        JCudfSerialization.SerializedTableHeader head;
        long numRows = 0;
        do {
          head = new JCudfSerialization.SerializedTableHeader(din);
          if (head.wasInitialized()) {
            HostMemoryBuffer buff = HostMemoryBuffer.allocate(head.dataLen);
            buffers.add(buff);
            JCudfSerialization.readTableIntoBuffer(din, head, buff);
            assert head.wasDataRead();
            numRows += head.getNumRows();
            assert numRows <= Integer.MAX_VALUE;
            headers.add(head);
          }
        } while (head.wasInitialized());
        assert numRows == t.getRowCount();
        try (Table found = JCudfSerialization.readAndConcat(
            headers.toArray(new JCudfSerialization.SerializedTableHeader[headers.size()]),
            buffers.toArray(new HostMemoryBuffer[buffers.size()]))) {
          assertTablesAreEqual(t, found);
        }
      } finally {
        for (HostMemoryBuffer buff: buffers) {
          buff.close();
        }
      }
    }
  }

  @Test
  void testRoundRobinPartition() {
    try (Table t = new Table.TestBuilder()
        .column(     100,      202,      3003,    40004,        5,      -60,       1,      null,        3,  null,        5,     null,        7, null,        9,      null,       11,      null,        13,      null,       15)
        .column(    true,     true,     false,    false,     true,     null,     true,     true,     null, false,    false,     null,     true, true,     null,     false,    false,      null,      true,      true,     null)
        .column( (byte)1,  (byte)2,      null,  (byte)4,  (byte)5,  (byte)6,  (byte)1,  (byte)2,  (byte)3,  null,  (byte)5,  (byte)6,  (byte)7, null,  (byte)9,  (byte)10, (byte)11,      null,  (byte)13,  (byte)14, (byte)15)
        .column((short)6, (short)5,  (short)4,     null, (short)2, (short)1, (short)1, (short)2, (short)3,  null, (short)5, (short)6, (short)7, null, (short)9, (short)10,     null, (short)12, (short)13, (short)14,     null)
        .column(      1L,     null,     1001L,      50L,   -2000L,     null,       1L,       2L,       3L,    4L,     null,       6L,       7L,   8L,       9L,      null,      11L,       12L,       13L,       14L,     null)
        .column(   10.1f,      20f, Float.NaN,  3.1415f,     -60f,     null,       1f,       2f,       3f,    4f,       5f,     null,       7f,   8f,       9f,       10f,      11f,      null,       13f,       14f,      15f)
        .column(    10.1,     20.0,      33.1,   3.1415,    -60.5,     null,       1.,       2.,       3.,    4.,       5.,       6.,     null,   8.,       9.,       10.,      11.,       12.,      null,       14.,      15.)
        .timestampDayColumn(99, 100,      101,      102,      103,      104,        1,        2,        3,     4,        5,        6,        7, null,        9,        10,       11,        12,        13,      null,       15)
        .timestampMillisecondsColumn(9L, 1006L, 101L, 5092L, null,      88L,       1L,       2L,       3L,    4L,       5L,       6L,       7L,   8L,     null,       10L,      11L,       12L,       13L,       14L,      15L)
        .timestampSecondsColumn(1L, null,  3L,       4L,       5L,       6L,       1L,       2L,       3L,    4L,       5L,       6L,       7L,   8L,       9L,      null,      11L,       12L,       13L,       14L,      15L)
        .column(     "A",      "B",       "C",      "D",     null, "TESTING",     "1",      "2",      "3",   "4",      "5",      "6",      "7", null,      "9",      "10",     "11",      "12",      "13",      null,     "15")
        .column(     "A",      "A",       "C",      "C",     null, "TESTING",     "1",      "2",      "3",   "4",      "5",      "6",      "7", null,      "9",      "10",     "11",      "12",      "13",      null,     "15")
        .build()) {
      try (Table expectedTable = new Table.TestBuilder()
          .column(     100,   40004,        1,  null,        7,      null,        13,      202,        5,     null,        5, null,       11,      null,      3003,       -60,        3,     null,        9,      null,       15)
          .column(    true,   false,     true, false,     true,     false,      true,     true,     true,     true,    false, true,    false,      true,     false,      null,     null,     null,     null,      null,     null)
          .column( (byte)1, (byte)4,  (byte)1,  null,  (byte)7,  (byte)10,  (byte)13,  (byte)2,  (byte)5,  (byte)2,  (byte)5, null, (byte)11,  (byte)14,      null,   (byte)6,  (byte)3,  (byte)6,  (byte)9,      null, (byte)15)
          .column((short)6,    null, (short)1,  null, (short)7, (short)10, (short)13, (short)5, (short)2, (short)2, (short)5, null,     null, (short)14,  (short)4,  (short)1, (short)3, (short)6, (short)9, (short)12,     null)
          .column(      1L,     50L,       1L,    4L,       7L,      null,       13L,     null,   -2000L,       2L,     null,   8L,      11L,       14L,     1001L,      null,       3L,       6L,       9L,       12L,     null)
          .column(   10.1f, 3.1415f,       1f,    4f,       7f,       10f,       13f,      20f,     -60f,       2f,       5f,   8f,      11f,       14f, Float.NaN,      null,       3f,     null,       9f,      null,      15f)
          .column(    10.1,  3.1415,       1.,    4.,     null,       10.,      null,     20.0,    -60.5,       2.,       5.,   8.,      11.,       14.,      33.1,      null,       3.,       6.,       9.,       12.,      15.)
          .timestampDayColumn(99, 102,      1,     4,        7,        10,        13,      100,      103,        2,        5, null,       11,      null,       101,       104,        3,        6,        9,        12,       15)
          .timestampMillisecondsColumn(9L, 5092L, 1L, 4L,   7L,       10L,       13L,    1006L,     null,       2L,       5L,   8L,      11L,       14L,      101L,       88L,       3L,       6L,     null,       12L,      15L)
          .timestampSecondsColumn(1L, 4L,   1L,   4L,       7L,      null,       13L,     null,       5L,       2L,       5L,   8L,      11L,       14L,        3L,        6L,       3L,       6L,       9L,       12L,      15L)
          .column(     "A",     "D",       "1",  "4",      "7",      "10",      "13",      "B",     null,      "2",      "5", null,     "11",      null,       "C", "TESTING",      "3",      "6",      "9",      "12",     "15")
          .column(     "A",     "C",       "1",  "4",      "7",      "10",      "13",      "A",     null,      "2",      "5", null,     "11",      null,       "C", "TESTING",      "3",      "6",      "9",      "12",     "15")
          .build();
           PartitionedTable pt = t.roundRobinPartition(3, 0)) {
        assertTablesAreEqual(expectedTable, pt.getTable());
        int[] parts = pt.getPartitions();
        assertEquals(3, parts.length);
        assertEquals(0, parts[0]);
        assertEquals(7, parts[1]);
        assertEquals(14, parts[2]);
      }

      try (Table expectedTable = new Table.TestBuilder()
          .column(      3003,       -60,        3,     null,        9,      null,       15,     100,   40004,        1,  null,        7,      null,        13,      202,        5,     null,        5, null,       11,      null)
          .column(     false,      null,     null,     null,     null,      null,     null,    true,   false,     true, false,     true,     false,      true,     true,     true,     true,    false, true,    false,      true)
          .column(      null,   (byte)6,  (byte)3,  (byte)6,  (byte)9,      null, (byte)15, (byte)1, (byte)4,  (byte)1,  null,  (byte)7,  (byte)10,  (byte)13,  (byte)2,  (byte)5,  (byte)2,  (byte)5, null, (byte)11,  (byte)14)
          .column(  (short)4,  (short)1, (short)3, (short)6, (short)9, (short)12,     null,(short)6,    null, (short)1,  null, (short)7, (short)10, (short)13, (short)5, (short)2, (short)2, (short)5, null,     null, (short)14)
          .column(     1001L,      null,       3L,       6L,       9L,       12L,     null,      1L,     50L,       1L,    4L,       7L,      null,       13L,     null,   -2000L,       2L,     null,   8L,      11L,       14L)
          .column( Float.NaN,      null,       3f,     null,       9f,      null,      15f,   10.1f, 3.1415f,       1f,    4f,       7f,       10f,       13f,      20f,     -60f,       2f,       5f,   8f,      11f,       14f)
          .column(      33.1,      null,       3.,       6.,       9.,       12.,      15.,    10.1,  3.1415,       1.,    4.,     null,       10.,      null,     20.0,    -60.5,       2.,       5.,   8.,      11.,       14.)
          .timestampDayColumn(101, 104,         3,        6,        9,        12,       15,      99,     102,        1,     4,        7,        10,        13,      100,      103,        2,        5, null,       11,      null)
          .timestampMillisecondsColumn(101L, 88L, 3L,    6L,     null,       12L,      15L,      9L,   5092L,       1L,    4L,       7L,       10L,       13L,    1006L,     null,       2L,       5L,   8L,      11L,       14L)
          .timestampSecondsColumn(3L, 6L,      3L,       6L,       9L,       12L,      15L,      1L,      4L,       1L,    4L,       7L,      null,       13L,     null,       5L,       2L,       5L,   8L,      11L,       14L)
          .column(       "C", "TESTING",      "3",      "6",      "9",      "12",     "15",     "A",     "D",       "1",  "4",      "7",      "10",      "13",      "B",     null,      "2",      "5", null,     "11",      null)
          .column(       "C", "TESTING",      "3",      "6",      "9",      "12",     "15",     "A",     "C",       "1",  "4",      "7",      "10",      "13",      "A",     null,      "2",      "5", null,     "11",      null)
          .build();
           PartitionedTable pt = t.roundRobinPartition(3, 1)) {
        assertTablesAreEqual(expectedTable, pt.getTable());
        int[] parts = pt.getPartitions();
        assertEquals(3, parts.length);
        assertEquals(0, parts[0]);
        assertEquals(7, parts[1]);
        assertEquals(14, parts[2]);
      }

      try (Table expectedTable = new Table.TestBuilder()
          .column(      202,        5,     null,        5, null,       11,      null,      3003,       -60,        3,     null,        9,      null,       15,     100,   40004,        1,  null,        7,      null,        13)
          .column(     true,     true,     true,    false, true,    false,      true,     false,      null,     null,     null,     null,      null,     null,    true,   false,     true, false,     true,     false,      true)
          .column(  (byte)2,  (byte)5,  (byte)2,  (byte)5, null, (byte)11,  (byte)14,      null,   (byte)6,  (byte)3,  (byte)6,  (byte)9,      null, (byte)15, (byte)1, (byte)4,  (byte)1,  null,  (byte)7,  (byte)10,  (byte)13)
          .column( (short)5, (short)2, (short)2, (short)5, null,     null, (short)14,  (short)4,  (short)1, (short)3, (short)6, (short)9, (short)12,     null,(short)6,    null, (short)1,  null, (short)7, (short)10, (short)13)
          .column(     null,   -2000L,       2L,     null,   8L,      11L,       14L,     1001L,      null,       3L,       6L,       9L,       12L,     null,      1L,     50L,       1L,    4L,       7L,      null,       13L)
          .column(      20f,     -60f,       2f,       5f,   8f,      11f,       14f, Float.NaN,      null,       3f,     null,       9f,      null,      15f,   10.1f, 3.1415f,       1f,    4f,       7f,       10f,       13f)
          .column(     20.0,    -60.5,       2.,       5.,   8.,      11.,       14.,      33.1,      null,       3.,       6.,       9.,       12.,      15.,    10.1,  3.1415,       1.,    4.,     null,       10.,      null)
          .timestampDayColumn(100, 103,       2,        5, null,       11,      null,       101,       104,        3,        6,        9,        12,       15,      99,     102,        1,     4,        7,        10,        13)
          .timestampMillisecondsColumn(1006L, null, 2L, 5L,  8L,      11L,       14L,      101L,      88L,       3L,       6L,      null,       12L,      15L,      9L,   5092L,       1L,    4L,       7L,       10L,       13L)
          .timestampSecondsColumn(null, 5L,  2L,       5L,   8L,      11L,       14L,        3L,        6L,       3L,       6L,       9L,       12L,      15L,      1L,      4L,       1L,    4L,       7L,      null,       13L)
          .column(      "B",     null,      "2",      "5", null,     "11",      null,       "C", "TESTING",      "3",      "6",      "9",      "12",     "15",     "A",     "D",       "1",  "4",      "7",      "10",      "13")
          .column(      "A",     null,      "2",      "5", null,     "11",      null,       "C", "TESTING",      "3",      "6",      "9",      "12",     "15",     "A",     "C",       "1",  "4",      "7",      "10",      "13")
          .build();
           PartitionedTable pt = t.roundRobinPartition(3, 2)) {
        assertTablesAreEqual(expectedTable, pt.getTable());
        int[] parts = pt.getPartitions();
        assertEquals(3, parts.length);
        assertEquals(0, parts[0]);
        assertEquals(7, parts[1]);
        assertEquals(14, parts[2]);
      }
    }
  }

  @Test
  void testSerializationRoundTripConcatHostSide() throws IOException {
    try (Table t = new Table.TestBuilder()
        .column(     100,      202,      3003,    40004,        5,      -60,    1, null,    3,  null,     5, null,    7, null,   9,   null,    11, null,   13, null,  15)
        .column(    true,     true,     false,    false,     true,     null, true, true, null, false, false, null, true, true, null, false, false, null, true, true, null)
        .column( (byte)1,  (byte)2,      null,  (byte)4,  (byte)5,  (byte)6, (byte)1, (byte)2, (byte)3, null, (byte)5, (byte)6, (byte)7, null, (byte)9, (byte)10, (byte)11, null, (byte)13, (byte)14, (byte)15)
        .column((short)6, (short)5,  (short)4,     null, (short)2, (short)1, (short)1, (short)2, (short)3, null, (short)5, (short)6, (short)7, null, (short)9, (short)10, null, (short)12, (short)13, (short)14, null)
        .column(      1L,     null,     1001L,      50L,   -2000L,     null, 1L, 2L, 3L, 4L, null, 6L, 7L, 8L, 9L, null, 11L, 12L, 13L, 14L, null)
        .column(   10.1f,      20f, Float.NaN,  3.1415f,     -60f,     null, 1f, 2f, 3f, 4f, 5f, null, 7f, 8f, 9f, 10f, 11f, null, 13f, 14f, 15f)
        .column(    10.1,     20.0,      33.1,   3.1415,    -60.5,     null, 1., 2., 3., 4., 5., 6., null, 8., 9., 10., 11., 12., null, 14., 15.)
        .timestampDayColumn(99,      100,      101,      102,      103,      104, 1, 2, 3, 4, 5, 6, 7, null, 9, 10, 11, 12, 13, null, 15)
        .timestampMillisecondsColumn(9L,    1006L,     101L,    5092L,     null,      88L, 1L, 2L, 3L, 4L, 5L ,6L, 7L, 8L, null, 10L, 11L, 12L, 13L, 14L, 15L)
        .timestampSecondsColumn(1L, null, 3L, 4L, 5L, 6L, 1L, 2L, 3L, 4L, 5L ,6L, 7L, 8L, 9L, null, 11L, 12L, 13L, 14L, 15L)
        .column(     "A",      "B",      "C",      "D",     null,   "TESTING", "1", "2", "3", "4", "5", "6", "7", null, "9", "10", "11", "12", "13", null, "15")
        .column(     "A",      "A",      "C",      "C",     null,   "TESTING", "1", "2", "3", "4", "5", "6", "7", null, "9", "10", "11", "12", "13", null, "15")
        .build()) {
      for (int sliceAmount = 1; sliceAmount < t.getRowCount(); sliceAmount ++) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        for (int i = 0; i < t.getRowCount(); i += sliceAmount) {
          int len = (int) Math.min(t.getRowCount() - i, sliceAmount);
          JCudfSerialization.writeToStream(t, bout, i, len);
        }
        ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
        DataInputStream din = new DataInputStream(bin);
        ArrayList<JCudfSerialization.SerializedTableHeader> headers = new ArrayList<>();
        List<HostMemoryBuffer> buffers = new ArrayList<>();
        try {
          JCudfSerialization.SerializedTableHeader head;
          long numRows = 0;
          do {
            head = new JCudfSerialization.SerializedTableHeader(din);
            if (head.wasInitialized()) {
              HostMemoryBuffer buff = HostMemoryBuffer.allocate(100 * 1024);
              buffers.add(buff);
              JCudfSerialization.readTableIntoBuffer(din, head, buff);
              assert head.wasDataRead();
              numRows += head.getNumRows();
              assert numRows <= Integer.MAX_VALUE;
              headers.add(head);
            }
          } while (head.wasInitialized());
          assert numRows == t.getRowCount();
          try (Table found = JCudfSerialization.readAndConcat(
              headers.toArray(new JCudfSerialization.SerializedTableHeader[headers.size()]),
              buffers.toArray(new HostMemoryBuffer[buffers.size()]))) {
            assertPartialTablesAreEqual(t, 0, t.getRowCount(), found, false);
          }
        } finally {
          for (HostMemoryBuffer buff: buffers) {
            buff.close();
          }
        }
      }
    }
  }

  @Test
  void testConcatHost() throws IOException {
    try (Table t1 = new Table.TestBuilder()
        .column(
            1, 2, null, 4, 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
            1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null)
        .build();
         Table expected = new Table.TestBuilder()
             .column(
                 null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null)
             .build();
         Table t2 = t1.concatenate(t1, t1)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      JCudfSerialization.writeToStream(t2, out, 10, t2.getRowCount() - 10);
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
      JCudfSerialization.SerializedTableHeader header = new JCudfSerialization.SerializedTableHeader(in);
      assert header.wasInitialized();
      try (HostMemoryBuffer buff = HostMemoryBuffer.allocate(header.dataLen)) {
        JCudfSerialization.readTableIntoBuffer(in, header, buff);
        assert header.wasDataRead();
        try (Table result = JCudfSerialization.readAndConcat(
            new JCudfSerialization.SerializedTableHeader[] {header, header},
            new HostMemoryBuffer[] {buff, buff})) {
          assertPartialTablesAreEqual(expected, 0, expected.getRowCount(), result, false);
        }
      }
    }
  }

  @Test
  void testSerializationRoundTripSlicedHostSide() throws IOException {
    try (Table t = new Table.TestBuilder()
        .column(     100,      202,     3003,    40004,        5,      -60,    1, null,    3,  null,     5, null,    7, null,   9,   null,    11, null,   13, null,  15)
        .column(    true,     true,    false,    false,     true,     null, true, true, null, false, false, null, true, true, null, false, false, null, true, true, null)
        .column( (byte)1,  (byte)2,     null,  (byte)4,  (byte)5,  (byte)6, (byte)1, (byte)2, (byte)3, null, (byte)5, (byte)6, (byte)7, null, (byte)9, (byte)10, (byte)11, null, (byte)13, (byte)14, (byte)15)
        .column((short)6, (short)5, (short)4,     null, (short)2, (short)1, (short)1, (short)2, (short)3, null, (short)5, (short)6, (short)7, null, (short)9, (short)10, null, (short)12, (short)13, (short)14, null)
        .column(      1L,     null,    1001L,      50L,   -2000L,     null, 1L, 2L, 3L, 4L, null, 6L, 7L, 8L, 9L, null, 11L, 12L, 13L, 14L, null)
        .column(   10.1f,      20f,Float.NaN,  3.1415f,     -60f,     null, 1f, 2f, 3f, 4f, 5f, null, 7f, 8f, 9f, 10f, 11f, null, 13f, 14f, 15f)
        .column(    10.1,     20.0,     33.1,   3.1415,    -60.5,     null, 1., 2., 3., 4., 5., 6., null, 8., 9., 10., 11., 12., null, 14., 15.)
        .timestampDayColumn(99,      100,      101,      102,      103,      104, 1, 2, 3, 4, 5, 6, 7, null, 9, 10, 11, 12, 13, null, 15)
        .timestampMillisecondsColumn(9L,    1006L,     101L,    5092L,     null,      88L, 1L, 2L, 3L, 4L, 5L ,6L, 7L, 8L, null, 10L, 11L, 12L, 13L, 14L, 15L)
        .timestampSecondsColumn(1L, null, 3L, 4L, 5L, 6L, 1L, 2L, 3L, 4L, 5L ,6L, 7L, 8L, 9L, null, 11L, 12L, 13L, 14L, 15L)
        .column(     "A",      "B",      "C",      "D",     null,   "TESTING", "1", "2", "3", "4", "5", "6", "7", null, "9", "10", "11", "12", "13", null, "15")
        .column(     "A",      "A",      "C",      "C",     null,   "TESTING", "1", "2", "3", "4", "5", "6", "7", null, "9", "10", "11", "12", "13", null, "15")
        .build()) {
      for (int sliceAmount = 1; sliceAmount < t.getRowCount(); sliceAmount ++) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        for (int i = 0; i < t.getRowCount(); i += sliceAmount) {
          int len = (int) Math.min(t.getRowCount() - i, sliceAmount);
          JCudfSerialization.writeToStream(t, bout, i, len);
        }
        ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
        DataInputStream din = new DataInputStream(bin);
        ArrayList<JCudfSerialization.SerializedTableHeader> headers = new ArrayList<>();
        List<HostMemoryBuffer> buffers = new ArrayList<>();
        try {
          JCudfSerialization.SerializedTableHeader head;
          long numRows = 0;
          do {
            head = new JCudfSerialization.SerializedTableHeader(din);
            if (head.wasInitialized()) {
              HostMemoryBuffer buff = HostMemoryBuffer.allocate(100 * 1024);
              buffers.add(buff);
              JCudfSerialization.readTableIntoBuffer(din, head, buff);
              assert head.wasDataRead();
            }
            numRows += head.getNumRows();
            assert numRows <= Integer.MAX_VALUE;
            headers.add(head);
          } while (head.wasInitialized());
          assert numRows == t.getRowCount();
          ByteArrayOutputStream bout2 = new ByteArrayOutputStream();
          JCudfSerialization.writeConcatedStream(
              headers.toArray(new JCudfSerialization.SerializedTableHeader[headers.size()]),
              buffers.toArray(new HostMemoryBuffer[buffers.size()]), bout2);
          ByteArrayInputStream bin2 = new ByteArrayInputStream(bout2.toByteArray());
          try (JCudfSerialization.TableAndRowCountPair found = JCudfSerialization.readTableFrom(bin2)) {
            assertPartialTablesAreEqual(t, 0, t.getRowCount(), found.getTable(), false);
          }
          assertNull(JCudfSerialization.readTableFrom(bin2).getTable());
        } finally {
          for (HostMemoryBuffer buff: buffers) {
            buff.close();
          }
        }
      }
    }
  }

  @Test
  void testSerializationRoundTripSliced() throws IOException {
    try (Table t = new Table.TestBuilder()
        .column(     100,      202,     3003,    40004,        5,      -60,    1, null,    3,  null,     5, null,    7, null,   9,   null,    11, null,   13, null,  15)
        .column(    true,     true,    false,    false,     true,     null, true, true, null, false, false, null, true, true, null, false, false, null, true, true, null)
        .column( (byte)1,  (byte)2,     null,  (byte)4,  (byte)5,  (byte)6, (byte)1, (byte)2, (byte)3, null, (byte)5, (byte)6, (byte)7, null, (byte)9, (byte)10, (byte)11, null, (byte)13, (byte)14, (byte)15)
        .column((short)6, (short)5, (short)4,     null, (short)2, (short)1, (short)1, (short)2, (short)3, null, (short)5, (short)6, (short)7, null, (short)9, (short)10, null, (short)12, (short)13, (short)14, null)
        .column(      1L,     null,    1001L,      50L,   -2000L,     null, 1L, 2L, 3L, 4L, null, 6L, 7L, 8L, 9L, null, 11L, 12L, 13L, 14L, null)
        .column(   10.1f,      20f,Float.NaN,  3.1415f,     -60f,     null, 1f, 2f, 3f, 4f, 5f, null, 7f, 8f, 9f, 10f, 11f, null, 13f, 14f, 15f)
        .column(    10.1,     20.0,     33.1,   3.1415,    -60.5,     null, 1., 2., 3., 4., 5., 6., null, 8., 9., 10., 11., 12., null, 14., 15.)
        .timestampDayColumn(99,      100,      101,      102,      103,      104, 1, 2, 3, 4, 5, 6, 7, null, 9, 10, 11, 12, 13, null, 15)
        .timestampMillisecondsColumn(9L,    1006L,     101L,    5092L,     null,      88L, 1L, 2L, 3L, 4L, 5L ,6L, 7L, 8L, null, 10L, 11L, 12L, 13L, 14L, 15L)
        .timestampSecondsColumn(1L, null, 3L, 4L, 5L, 6L, 1L, 2L, 3L, 4L, 5L ,6L, 7L, 8L, 9L, null, 11L, 12L, 13L, 14L, 15L)
        .column(     "A",      "B",      "C",      "D",     null,   "TESTING", "1", "2", "3", "4", "5", "6", "7", null, "9", "10", "11", "12", "13", null, "15")
        .column(     "A",      "A",      "C",      "C",     null,   "TESTING", "1", "2", "3", "4", "5", "6", "7", null, "9", "10", "11", "12", "13", null, "15")
        .build()) {
      for (int sliceAmount = 1; sliceAmount < t.getRowCount(); sliceAmount ++) {
        for (int i = 0; i < t.getRowCount(); i += sliceAmount) {
          ByteArrayOutputStream bout = new ByteArrayOutputStream();
          int len = (int) Math.min(t.getRowCount() - i, sliceAmount);
          JCudfSerialization.writeToStream(t, bout, i, len);
          ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
          try (JCudfSerialization.TableAndRowCountPair found = JCudfSerialization.readTableFrom(bin)) {
            assertPartialTablesAreEqual(t, i, len, found.getTable(), i == 0 && len == t.getRowCount());
          }
          assertNull(JCudfSerialization.readTableFrom(bin).getTable());
        }
      }
    }
  }

  @Test
  void testValidityFill() {
    byte[] buff = new byte[2];
    buff[0] = 0;
    int bitsToFill = (buff.length * 8) - 1;
    assertEquals(bitsToFill, JCudfSerialization.fillValidity(buff, 1, bitsToFill));
    assertEquals(buff[0], 0xFFFFFFFE);
    assertEquals(buff[1], 0xFFFFFFFF);
  }

  @Test
  void testGroupByCount() {
    try (Table t1 = new Table.TestBuilder().column( "1",  "1",  "1",  "1",  "1",  "1")
                                           .column(   1,    3,    3,    5,    5,    0)
                                           .column(12.0, 14.0, 13.0, 17.0, 17.0, 17.0)
                                           .build()) {
      try (Table t3 = t1.groupBy(0, 1).aggregate(count(0));
           HostColumnVector aggOut1 = t3.getColumn(2).copyToHost()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
        Map<Object, Integer> expectedAggregateResult = new HashMap() {
          {
            // value, count
            put(1, 2);
            put(2, 2);
          }
        };
        for (int i = 0; i < 4; ++i) {
          int key = aggOut1.getInt(i);
          assertTrue(expectedAggregateResult.containsKey(key));
          Integer count = expectedAggregateResult.get(key);
          if (count == 1) {
            expectedAggregateResult.remove(key);
          } else {
            expectedAggregateResult.put(key, count - 1);
          }
        }
      }
    }
  }

  @Test
  void testGroupByCountWithNulls() {
    try (Table t1 = new Table.TestBuilder().column(null, null,    1,    1,    1,    1)
                                           .column(   1,    1,    1,    1,    1,    1)
                                           .column(   1,    1, null, null,    1,    1)
                                           .column(   1,    1,    1, null,    1,    1)
                                           .build()) {
      try (Table tmp = t1.groupBy(0).aggregate(count(1), count(2), count(3));
           Table t3 = tmp.orderBy(Table.asc(0, true));
           HostColumnVector groupCol = t3.getColumn(0).copyToHost();
           HostColumnVector countCol = t3.getColumn(1).copyToHost();
           HostColumnVector nullCountCol = t3.getColumn(2).copyToHost();
           HostColumnVector nullCountCol2 = t3.getColumn(3).copyToHost()) {
        // verify t3
        assertEquals(2, t3.getRowCount());

        // compare the grouping columns
        assertTrue(groupCol.isNull(0));
        assertEquals(groupCol.getInt(1), 1);

        // compare the agg columns
        // count(1)
        assertEquals(countCol.getInt(0), 2);
        assertEquals(countCol.getInt(1), 4);

        // count(2)
        assertEquals(nullCountCol.getInt(0), 2);
        assertEquals(nullCountCol.getInt(1), 2); // counts only the non-nulls

        // count(3)
        assertEquals(nullCountCol2.getInt(0), 2);
        assertEquals(nullCountCol2.getInt(1), 3); // counts only the non-nulls
      }
    }
  }

  @Test
  void testGroupByCountWithCollapsingNulls() {
    try (Table t1 = new Table.TestBuilder()
        .column(null, null,    1,    1,    1,    1)
        .column(   1,    1,    1,    1,    1,    1)
        .column(   1,    1, null, null,    1,    1)
        .column(   1,    1,    1, null,    1,    1)
        .build()) {

      GroupByOptions options = GroupByOptions.builder()
          .withIgnoreNullKeys(true)
          .build();

      try (Table tmp = t1.groupBy(options, 0).aggregate(count(1), count(2), count(3));
           Table t3 = tmp.orderBy(Table.asc(0, true));
           HostColumnVector groupCol = t3.getColumn(0).copyToHost();
           HostColumnVector countCol = t3.getColumn(1).copyToHost();
           HostColumnVector nullCountCol = t3.getColumn(2).copyToHost();
           HostColumnVector nullCountCol2 = t3.getColumn(3).copyToHost()) {
        // (null, 1) => became (1) because we are ignoring nulls
        assertEquals(1, t3.getRowCount());

        // compare the grouping columns
        assertEquals(groupCol.getInt(0), 1);

        // compare the agg columns
        // count(1)
        assertEquals(countCol.getInt(0), 4);

        // count(2)
        assertEquals(nullCountCol.getInt(0), 2); // counts only the non-nulls

        // count(3)
        assertEquals(nullCountCol2.getInt(0), 3); // counts only the non-nulls
      }
    }
  }

  @Test
  void testGroupByMax() {
    try (Table t1 = new Table.TestBuilder().column(   1,    1,    1,    1,    1,    1)
                                           .column(   1,    3,    3,    5,    5,    0)
                                           .column(12.0, 14.0, 13.0, 17.0, 17.0, 17.0)
                                           .build()) {
      try (Table t3 = t1.groupBy(0, 1).aggregate(max(2));
           HostColumnVector aggOut1 = t3.getColumn(2).copyToHost()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
        Map<Double, Integer> expectedAggregateResult = new HashMap() {
          {
            // value, count
            put(12.0, 1);
            put(14.0, 1);
            put(17.0, 2);
          }
        };
        for (int i = 0; i < 4; ++i) {
          Double key = aggOut1.getDouble(i);
          assertTrue(expectedAggregateResult.containsKey(key));
          Integer count = expectedAggregateResult.get(key);
          if (count == 1) {
            expectedAggregateResult.remove(key);
          } else {
            expectedAggregateResult.put(key, count - 1);
          }
        }
      }
    }
  }

  @Test
  void testGroupByMinBool() {
    try (Table t1 = new Table.TestBuilder()
        .column(true, null, false, true, null, null)
        .column(   1,    1,     2,    2,    3,    3).build();
         Table other = t1.groupBy(1).aggregate(min(0));
         Table ordered = other.orderBy(Table.asc(0));
         Table expected = new Table.TestBuilder()
             .column(1, 2, 3)
             .column (true, false, null)
             .build()) {
      assertTablesAreEqual(expected, ordered);
    }
  }

  @Test
  void testGroupByMaxBool() {
    try (Table t1 = new Table.TestBuilder()
        .column(false, null, false, true, null, null)
        .column(   1,    1,     2,    2,    3,    3).build();
         Table other = t1.groupBy(1).aggregate(max(0));
         Table ordered = other.orderBy(Table.asc(0));
         Table expected = new Table.TestBuilder()
             .column(1, 2, 3)
             .column (false, true, null)
             .build()) {
      assertTablesAreEqual(expected, ordered);
    }
  }

  @Test
  void testGroupByDuplicateAggregates() {
    try (Table t1 = new Table.TestBuilder().column(   1,    1,    1,    1,    1,    1)
                                           .column(   1,    3,    3,    5,    5,    0)
                                           .column(12.0, 14.0, 13.0, 15.0, 17.0, 18.0)
                                           .build();
         Table expected = new Table.TestBuilder()
             .column(1, 1, 1, 1)
             .column(1, 3, 5, 0)
             .column(12.0, 14.0, 17.0, 18.0)
             .column(12.0, 13.0, 15.0, 18.0)
             .column(12.0, 13.0, 15.0, 18.0)
             .column(12.0, 14.0, 17.0, 18.0)
             .column(12.0, 13.0, 15.0, 18.0)
             .column(   1,    2,    2,    1).build()) {
      try (Table t3 = t1.groupBy(0, 1)
          .aggregate(max(2), min(2), min(2), max(2), min(2), count(1));
          Table t4 = t3.orderBy(Table.asc(2))) {
        // verify t4
        assertEquals(4, t4.getRowCount());
        assertTablesAreEqual(t4, expected);

        assertEquals(t3.getColumn(0).getRefCount(), 1);
        assertEquals(t3.getColumn(1).getRefCount(), 1);
        assertEquals(t3.getColumn(2).getRefCount(), 2);
        assertEquals(t3.getColumn(3).getRefCount(), 3);
        assertEquals(t3.getColumn(4).getRefCount(), 3);
        assertEquals(t3.getColumn(5).getRefCount(), 2);
        assertEquals(t3.getColumn(6).getRefCount(), 3);
      }
    }
  }

  @Test
  void testGroupByMin() {
    try (Table t1 = new Table.TestBuilder().column(   1,    1,    1,    1,    1,    1)
                                           .column(   1,    3,    3,    5,    5,    0)
                                           .column(  12,   14,   13,   17,   17,   17)
                                           .build()) {
      try (Table t3 = t1.groupBy(0, 1).aggregate(min(2));
           HostColumnVector aggOut0 = t3.getColumn(2).copyToHost()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
        Map<Integer, Integer> expectedAggregateResult = new HashMap() {
          {
            // value, count
            put(12, 1);
            put(13, 1);
            put(17, 2);
          }
        };
        // check to see the aggregate column type depends on the source column
        // in this case the source column is Integer, therefore the result should be Integer type
        assertEquals(DType.INT32, aggOut0.getType());
        for (int i = 0; i < 4; ++i) {
          int key = aggOut0.getInt(i);
          assertTrue(expectedAggregateResult.containsKey(key));
          Integer count = expectedAggregateResult.get(key);
          if (count == 1) {
            expectedAggregateResult.remove(key);
          } else {
            expectedAggregateResult.put(key, count - 1);
          }
        }
      }
    }
  }

  @Test
  void testGroupBySum() {
    try (Table t1 = new Table.TestBuilder().column(   1,    1,    1,    1,    1,    1)
                                           .column(   1,    3,    3,    5,    5,    0)
                                           .column(12.0, 14.0, 13.0, 17.0, 17.0, 17.0)
                                           .build()) {
      try (Table t3 = t1.groupBy(0, 1).aggregate(sum(2));
           HostColumnVector aggOut1 = t3.getColumn(2).copyToHost()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
        Map<Double, Integer> expectedAggregateResult = new HashMap() {
          {
            // value, count
            put(12.0, 1);
            put(27.0, 1);
            put(34.0, 1);
            put(17.0, 1);
          }
        };
        for (int i = 0; i < 4; ++i) {
          Double key = aggOut1.getDouble(i);
          assertTrue(expectedAggregateResult.containsKey(key));
          Integer count = expectedAggregateResult.get(key);
          if (count == 1) {
            expectedAggregateResult.remove(key);
          } else {
            expectedAggregateResult.put(key, count - 1);
          }
        }
      }
    }
  }

  @Test
  void testGroupByAvg() {
    try (Table t1 = new Table.TestBuilder().column( 1,  1,  1,  1,  1,  1)
                                           .column( 1,  3,  3,  5,  5,  0)
                                           .column(12, 14, 13,  1, 17, 17)
                                           .build()) {
      try (Table t3 = t1.groupBy(0, 1).aggregate(mean(2));
           HostColumnVector aggOut1 = t3.getColumn(2).copyToHost()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
        Map<Double, Integer> expectedAggregateResult = new HashMap() {
          {
            // value, count
            put(12.0, 1);
            put(13.5, 1);
            put(17.0, 1);
            put(9.0, 1);
          }
        };
        for (int i = 0; i < 4; ++i) {
          Double key = aggOut1.getDouble(i);
          assertTrue(expectedAggregateResult.containsKey(key));
          Integer count = expectedAggregateResult.get(key);
          if (count == 1) {
            expectedAggregateResult.remove(key);
          } else {
            expectedAggregateResult.put(key, count - 1);
          }
        }
      }
    }
  }

  @Test
  void testMultiAgg() {
    try (Table t1 = new Table.TestBuilder().column(  1,   1,   1,   1,   1,    1)
                                           .column(  2,   2,   2,   3,   3,    3)
                                           .column(5.0, 2.3, 3.4, 2.3, 1.3, 12.2)
                                           .column(  3,   1,   7,  -1,   9,    0)
                                           .build()) {
      try (Table t2 = t1.groupBy(0, 1).aggregate(count(0), max(3), min(2), mean(2), sum(2));
           HostColumnVector countOut = t2.getColumn(2).copyToHost();
           HostColumnVector maxOut = t2.getColumn(3).copyToHost();
           HostColumnVector minOut = t2.getColumn(4).copyToHost();
           HostColumnVector avgOut = t2.getColumn(5).copyToHost();
           HostColumnVector sumOut = t2.getColumn(6).copyToHost()) {
        assertEquals(2, t2.getRowCount());

        // verify count
        assertEquals(3, countOut.getInt(0));
        assertEquals(3, countOut.getInt(1));

        // verify mean
        List<Double> sortedMean = new ArrayList<>();
        sortedMean.add(avgOut.getDouble(0));
        sortedMean.add(avgOut.getDouble(1));
        sortedMean = sortedMean.stream()
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());

        assertEquals(3.5666f, sortedMean.get(0), 0.0001);
        assertEquals(5.2666f, sortedMean.get(1), 0.0001);

        // verify sum
        List<Double> sortedSum = new ArrayList<>();
        sortedSum.add(sumOut.getDouble(0));
        sortedSum.add(sumOut.getDouble(1));
        sortedSum = sortedSum.stream()
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());

        assertEquals(10.7f, sortedSum.get(0), 0.0001);
        assertEquals(15.8f, sortedSum.get(1), 0.0001);

        // verify min
        List<Double> sortedMin = new ArrayList<>();
        sortedMin.add(minOut.getDouble(0));
        sortedMin.add(minOut.getDouble(1));
        sortedMin = sortedMin.stream()
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());

        assertEquals(1.3f, sortedMin.get(0), 0.0001);
        assertEquals(2.3f, sortedMin.get(1), 0.0001);

        // verify max
        List<Integer> sortedMax = new ArrayList<>();
        sortedMax.add(maxOut.getInt(0));
        sortedMax.add(maxOut.getInt(1));
        sortedMax = sortedMax.stream()
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());

        assertEquals(7, sortedMax.get(0));
        assertEquals(9, sortedMax.get(1));
      }
    }
  }

  @Test
  void testSumWithStrings() {
    try (Table t = new Table.TestBuilder()
        .column("1-URGENT", "3-MEDIUM", "1-URGENT", "3-MEDIUM")
        .column(5289L, 5203L, 5303L, 5206L)
        .build();
         Table result = t.groupBy(0).aggregate(Table.sum(1));
         Table expected = new Table.TestBuilder()
             .column("1-URGENT", "3-MEDIUM")
             .column(5289L + 5303L, 5203L + 5206L)
             .build()) {
      assertTablesAreEqual(expected, result);
    }
  }

  @Test
  void testGroupByNoAggs() {
    try (Table t1 = new Table.TestBuilder().column(   1,    1,    1,    1,    1,    1)
        .column(   1,    3,    3,    5,    5,    0)
        .column(  12,   14,   13,   17,   17,   17)
        .build()) {
      try (Table t3 = t1.groupBy(0, 1).aggregate()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
      }
    }
  }


  @Test
  void testMaskWithoutValidity() {
    try (ColumnVector mask = ColumnVector.fromBoxedBooleans(true, false, true, false, true);
         ColumnVector fromInts = ColumnVector.fromInts(1, 2, 3, 4, 5);
         ColumnVector fromStrings = ColumnVector.fromStrings("1", "2", "3", "4", "5");
         Table input = new Table(fromInts, fromStrings);
         Table filteredTable = input.filter(mask);
         ColumnVector expectedInts = ColumnVector.fromInts(1, 3, 5);
         ColumnVector expectedStrings = ColumnVector.fromStrings("1", "3", "5");
         Table expected = new Table(expectedInts, expectedStrings)) {
      assertTablesAreEqual(expected, filteredTable);
    }
  }

  @Test
  void testMaskWithValidity() {
    final int numRows = 5;
    try (Builder builder = HostColumnVector.builder(DType.BOOL8, numRows)) {
      for (int i = 0; i < numRows; ++i) {
        builder.append((byte) 1);
        if (i % 2 != 0) {
          builder.setNullAt(i);
        }
      }
      try (ColumnVector mask = builder.buildAndPutOnDevice();
           ColumnVector fromInts = ColumnVector.fromBoxedInts(1, null, 2, 3, null);
           Table input = new Table(fromInts);
           Table filteredTable = input.filter(mask);
           HostColumnVector filtered = filteredTable.getColumn(0).copyToHost()) {
        assertEquals(DType.INT32, filtered.getType());
        assertEquals(3, filtered.getRowCount());
        assertEquals(1, filtered.getInt(0));
        assertEquals(2, filtered.getInt(1));
        assertTrue(filtered.isNull(2));
      }
    }
  }

  @Test
  void testMaskDataOnly() {
    byte[] maskVals = new byte[]{0, 1, 0, 1, 1};
    try (ColumnVector mask = ColumnVector.boolFromBytes(maskVals);
         ColumnVector fromBytes = ColumnVector.fromBoxedBytes((byte) 1, null, (byte) 2, (byte) 3, null);
         Table input = new Table(fromBytes);
         Table filteredTable = input.filter(mask);
         HostColumnVector filtered = filteredTable.getColumn(0).copyToHost()) {
      assertEquals(DType.INT8, filtered.getType());
      assertEquals(3, filtered.getRowCount());
      assertTrue(filtered.isNull(0));
      assertEquals(3, filtered.getByte(1));
      assertTrue(filtered.isNull(2));
    }
  }


  @Test
  void testAllFilteredFromData() {
    Boolean[] maskVals = new Boolean[5];
    Arrays.fill(maskVals, false);
    try (ColumnVector mask = ColumnVector.fromBoxedBooleans(maskVals);
         ColumnVector fromInts = ColumnVector.fromBoxedInts(1, null, 2, 3, null);
         Table input = new Table(fromInts);
         Table filteredTable = input.filter(mask)) {
      ColumnVector filtered = filteredTable.getColumn(0);
      assertEquals(DType.INT32, filtered.getType());
      assertEquals(0, filtered.getRowCount());
    }
  }

  @Test
  void testAllFilteredFromValidity() {
    final int numRows = 5;
    try (Builder builder = HostColumnVector.builder(DType.BOOL8, numRows)) {
      for (int i = 0; i < numRows; ++i) {
        builder.append((byte) 1);
        builder.setNullAt(i);
      }
      try (ColumnVector mask = builder.buildAndPutOnDevice();
           ColumnVector fromInts = ColumnVector.fromBoxedInts(1, null, 2, 3, null);
           Table input = new Table(fromInts);
           Table filteredTable = input.filter(mask)) {
        ColumnVector filtered = filteredTable.getColumn(0);
        assertEquals(DType.INT32, filtered.getType());
        assertEquals(0, filtered.getRowCount());
      }
    }
  }

  @Test
  void testMismatchedSizesForFilter() {
    Boolean[] maskVals = new Boolean[3];
    Arrays.fill(maskVals, true);
    try (ColumnVector mask = ColumnVector.fromBoxedBooleans(maskVals);
         ColumnVector fromInts = ColumnVector.fromBoxedInts(1, null, 2, 3, null);
         Table input = new Table(fromInts)) {
      assertThrows(AssertionError.class, () -> input.filter(mask).close());
    }
  }

  @Test
  void testTableBasedFilter() {
    byte[] maskVals = new byte[]{0, 1, 0, 1, 1};
    try (ColumnVector mask = ColumnVector.boolFromBytes(maskVals);
         ColumnVector fromInts = ColumnVector.fromBoxedInts(1, null, 2, 3, null);
         ColumnVector fromStrings = ColumnVector.fromStrings("one", "two", "three", null, "five");
         Table input = new Table(fromInts, fromStrings);
         Table filtered = input.filter(mask);
         ColumnVector expectedFromInts = ColumnVector.fromBoxedInts(null, 3, null);
         ColumnVector expectedFromStrings = ColumnVector.fromStrings("two", null, "five");
         Table expected = new Table(expectedFromInts, expectedFromStrings)) {
      assertTablesAreEqual(expected, filtered);
    }
  }

  private Table getExpectedFileTable() {
    return new TestBuilder()
        .column(true, false, false, true, false)
        .column(5, 1, 0, 2, 7)
        .column(new Byte[]{2, 3, 4, 5, 9})
        .column(3l, 9l, 4l, 2l, 20l)
        .column("this", "is", "a", "test", "string")
        .column(1.0f, 3.5f, 5.9f, 7.1f, 9.8f)
        .column(5.0d, 9.5d, 0.9d, 7.23d, 2.8d)
        .build();
  }

  @Test
  void testParquetWriteToFileNoNames() throws IOException {
    File tempFile = File.createTempFile("test-nonames", ".parquet");
    try (Table table0 = getExpectedFileTable()) {
      table0.writeParquet(tempFile.getAbsoluteFile());
      try (Table table1 = Table.readParquet(tempFile.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table1);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testParquetWriteToFileWithNames() throws IOException {
    File tempFile = File.createTempFile("test-names", ".parquet");
    try (Table table0 = getExpectedFileTable()) {
      ParquetWriterOptions options = ParquetWriterOptions.builder()
          .withColumnNames("first", "second", "third", "fourth", "fifth", "sixth")
          .withCompressionType(CompressionType.NONE)
          .withStatisticsFrequency(ParquetWriterOptions.StatisticsFrequency.NONE)
          .build();
      table0.writeParquet(options, tempFile.getAbsoluteFile());
      try (Table table2 = Table.readParquet(tempFile.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table2);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testParquetWriteToFileWithNamesAndMetadata() throws IOException {
    File tempFile = File.createTempFile("test-names-metadata", ".parquet");
    try (Table table0 = getExpectedFileTable()) {
      ParquetWriterOptions options = ParquetWriterOptions.builder()
          .withColumnNames("first", "second", "third", "fourth", "fifth", "sixth")
          .withMetadata("somekey", "somevalue")
          .withCompressionType(CompressionType.NONE)
          .withStatisticsFrequency(ParquetWriterOptions.StatisticsFrequency.NONE)
          .build();
      table0.writeParquet(options, tempFile.getAbsoluteFile());
      try (Table table2 = Table.readParquet(tempFile.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table2);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testParquetWriteToFileUncompressedNoStats() throws IOException {
    File tempFile = File.createTempFile("test-uncompressed", ".parquet");
    try (Table table0 = getExpectedFileTable()) {
      ParquetWriterOptions options = ParquetWriterOptions.builder()
          .withCompressionType(CompressionType.NONE)
          .withStatisticsFrequency(ParquetWriterOptions.StatisticsFrequency.NONE)
          .build();
      table0.writeParquet(options, tempFile.getAbsoluteFile());
      try (Table table2 = Table.readParquet(tempFile.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table2);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testORCWriteToFile() throws IOException {
    File tempFile = File.createTempFile("test", ".orc");
    try (Table table0 = getExpectedFileTable()) {
      table0.writeORC(tempFile.getAbsoluteFile());
      try (Table table1 = Table.readORC(tempFile.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table1);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testORCWriteToFileWithColNames() throws IOException {
    File tempFile = File.createTempFile("test", ".orc");
    final String[] colNames = new String[]{"bool", "int", "byte","long","str","float","double"};
    try (Table table0 = getExpectedFileTable()) {
      ORCWriterOptions options = ORCWriterOptions.builder()
          .withColumnNames(colNames)
          .withMetadata("somekey", "somevalue")
          .build();
      table0.writeORC(options, tempFile.getAbsoluteFile());
      ORCOptions opts = ORCOptions.builder().includeColumn(colNames).build();
      try (Table table1 = Table.readORC(opts, tempFile.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table1);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testORCWriteToFileUncompressed() throws IOException {
    File tempFileUncompressed = File.createTempFile("test-uncompressed", ".orc");
    try (Table table0 = getExpectedFileTable()) {
      table0.writeORC(ORCWriterOptions.builder().withCompressionType(CompressionType.NONE).build(), tempFileUncompressed.getAbsoluteFile());
      try (Table table2 = Table.readORC(tempFileUncompressed.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table2);
      }
    } finally {
      tempFileUncompressed.delete();
    }
  }
}
