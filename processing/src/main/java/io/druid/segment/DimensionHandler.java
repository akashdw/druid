/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.segment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;

import io.druid.java.util.common.io.smoosh.FileSmoosher;
import io.druid.query.dimension.DimensionSpec;
import io.druid.query.groupby.GroupByQueryEngine;
import io.druid.segment.column.Column;
import io.druid.segment.column.ColumnCapabilities;
import io.druid.segment.column.ValueType;
import io.druid.segment.data.IOPeon;
import io.druid.segment.data.Indexed;
import io.druid.segment.data.IndexedInts;

import java.io.Closeable;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Processing related interface
 *
 * A DimensionHandler is an object that encapsulates indexing, column merging/building, and querying operations
 * for a given dimension type (e.g., dict-encoded String, Long).
 *
 * These operations are handled by sub-objects created through a DimensionHandler's methods:
 *   DimensionIndexer, DimensionMerger, and DimensionColumnReader, respectively.
 *
 * Each DimensionHandler object is associated with a single dimension.
 *
 * This interface allows type-specific behavior column logic, such as choice of indexing structures and disk formats.
 * to be contained within a type-specific set of handler objects, simplifying processing classes
 * such as IncrementalIndex and IndexMerger and allowing for abstracted development of additional dimension types.
 *
 * A dimension may have two representations, an encoded representation and a actual representation.
 * For example, a value for a String dimension has an integer dictionary encoding, and an actual String representation.
 *
 * A DimensionHandler is a stateless object, and thus thread-safe; its methods should be pure functions.
 *
 * The EncodedType and ActualType are Comparable because columns used as dimensions must have sortable values.
 *
 * @param <EncodedType> class of the encoded values
 * @param <ActualType> class of the actual values
 */
public interface DimensionHandler<EncodedType extends Comparable<EncodedType>, EncodedTypeArray, ActualType extends Comparable<ActualType>>
{
  /**
   * Get the name of the column associated with this handler.
   *
   * This string would be the output name of the column during ingestion, and the name of an input column when querying.
   *
   * @return Dimension name
   */
  public String getDimensionName();


  /**
   * Creates a new DimensionIndexer, a per-dimension object responsible for processing ingested rows in-memory, used by the
   * IncrementalIndex. See {@link DimensionIndexer} interface for more information.
   *
   * @return A new DimensionIndexer object.
   */
  public DimensionIndexer<EncodedType, EncodedTypeArray, ActualType> makeIndexer();


  /**
   * Creates a new DimensionMergerV9, a per-dimension object responsible for merging indexes/row data across segments
   * and building the on-disk representation of a dimension. For use with IndexMergerV9 only.
   *
   * See {@link DimensionMergerV9} interface for more information.
   *
   * @param indexSpec     Specification object for the index merge
   * @param outDir        Location to store files generated by the merging process
   * @param ioPeon        ioPeon object passed in by IndexMerger, manages files created by the merging process
   * @param capabilities  The ColumnCapabilities of the dimension represented by this DimensionHandler
   * @param progress      ProgressIndicator used by the merging process

   * @return A new DimensionMergerV9 object.
   */
  public DimensionMergerV9<EncodedTypeArray> makeMerger(
      IndexSpec indexSpec,
      File outDir,
      IOPeon ioPeon,
      ColumnCapabilities capabilities,
      ProgressIndicator progress
  );


  /**
   * Creates a new DimensionMergerLegacy, a per-dimension object responsible for merging indexes/row data across segments
   * and building the on-disk representation of a dimension. For use with IndexMerger only.
   *
   * See {@link DimensionMergerLegacy} interface for more information.
   *
   * @param indexSpec     Specification object for the index merge
   * @param outDir        Location to store files generated by the merging process
   * @param ioPeon        ioPeon object passed in by IndexMerger, manages files created by the merging process
   * @param capabilities  The ColumnCapabilities of the dimension represented by this DimensionHandler
   * @param progress      ProgressIndicator used by the merging process

   * @return A new DimensionMergerLegacy object.
   */
  public DimensionMergerLegacy<EncodedTypeArray> makeLegacyMerger(
      IndexSpec indexSpec,
      File outDir,
      IOPeon ioPeon,
      ColumnCapabilities capabilities,
      ProgressIndicator progress
  );


  /**
   * Given an array representing a single set of row value(s) for this dimension as an Object,
   * return the length of the array after appropriate type-casting.
   *
   * For example, a dictionary encoded String dimension would receive an int[] as an Object.
   *
   * @param dimVals Array of row values
   * @return Size of dimVals
   */
  public int getLengthFromEncodedArray(EncodedTypeArray dimVals);


  /**
   * Given two arrays representing sorted encoded row value(s), return the result of their comparison.
   *
   * If the two arrays have different lengths, the shorter array should be ordered first in the comparison.
   *
   * Otherwise, this function should iterate through the array values and return the comparison of the first difference.
   *
   * @param lhs array of row values
   * @param rhs array of row values
   *
   * @return integer indicating comparison result of arrays
   */
  public int compareSortedEncodedArrays(EncodedTypeArray lhs, EncodedTypeArray rhs);


  /**
   * Given two arrays representing sorted encoded row value(s), check that the two arrays have the same encoded values,
   * or if the encoded values differ, that they translate into the same actual values, using the mappings
   * provided by lhsEncodings and rhsEncodings (if applicable).
   *
   * If validation fails, this method should throw a SegmentValidationException.
   *
   * Used by IndexIO for validating segments.
   *
   * See StringDimensionHandler.validateSortedEncodedArrays() for a reference implementation.
   *
   * @param lhs array of row values
   * @param rhs array of row values
   * @param lhsEncodings encoding lookup from lhs's segment, null if not applicable for this dimension's type
   * @param rhsEncodings encoding lookup from rhs's segment, null if not applicable for this dimension's type
   *
   * @return integer indicating comparison result of arrays
   */
  public void validateSortedEncodedArrays(
      EncodedTypeArray lhs,
      EncodedTypeArray rhs,
      Indexed<ActualType> lhsEncodings,
      Indexed<ActualType> rhsEncodings
  ) throws SegmentValidationException;


  /**
   * Given a Column, return a type-specific object that can be used to retrieve row values.
   *
   * For example:
   * - A String-typed implementation would return the result of column.getDictionaryEncoding()
   * - A long-typed implemention would return the result of column.getGenericColumn().
   *
   * @param column Column for this dimension from a QueryableIndex
   * @return The type-specific column subobject for this dimension.
   */
  public Closeable getSubColumn(Column column);


  /**
   * Given a subcolumn from getSubColumn, and the index of the current row, retrieve a row as an array of values.
   *
   * For example:
   * - A String-typed implementation would read the current row from a DictionaryEncodedColumn as an int[].
   * - A long-typed implemention would read the current row from a GenericColumn return the current row as a long[].
   *
   * @param column Column for this dimension from a QueryableIndex
   * @param currRow The index of the row to retrieve
   * @return The row from "column" specified by "currRow", as an array of values
   */
  public Object getRowValueArrayFromColumn(Closeable column, int currRow);
}
