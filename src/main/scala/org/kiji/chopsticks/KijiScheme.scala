/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.chopsticks

import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong

import scala.collection.JavaConverters._

import cascading.flow.FlowProcess
import cascading.scheme.Scheme
import cascading.scheme.SinkCall
import cascading.scheme.SourceCall
import cascading.tap.Tap
import cascading.tuple.Fields
import cascading.tuple.Tuple
import cascading.tuple.TupleEntry
import com.google.common.base.Objects
import org.apache.avro.util.Utf8
import org.apache.commons.codec.binary.Base64
import org.apache.commons.lang.SerializationUtils
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapred.OutputCollector
import org.apache.hadoop.mapred.RecordReader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.kiji.annotations.ApiAudience
import org.kiji.annotations.ApiStability
import org.kiji.chopsticks.Resources.doAndRelease
import org.kiji.mapreduce.framework.KijiConfKeys
import org.kiji.schema.EntityId
import org.kiji.schema.Kiji
import org.kiji.schema.KijiColumnName
import org.kiji.schema.KijiDataRequest
import org.kiji.schema.KijiDataRequestBuilder
import org.kiji.schema.KijiRowData
import org.kiji.schema.KijiTable
import org.kiji.schema.KijiTableWriter
import org.kiji.schema.KijiURI

/**
 * A scheme that can source and sink data from a Kiji table. This scheme is responsible for
 * converting rows from a Kiji table that are input to a Cascading flow into Cascading tuples (see
 * `source(cascading.flow.FlowProcess, cascading.scheme.SourceCall)`) and writing output
 * data from a Cascading flow to a Kiji table
 * (see `sink(cascading.flow.FlowProcess, cascading.scheme.SinkCall)`).
 *
 * @param timeRange to include from the Kiji table.
 * @param timestampField is the name of a tuple field that will contain cell timestamp when the
 * @param loggingInterval The interval to log skipped rows on.
 *    For example, if loggingInterval is 1000, then every 1000th skipped row will be logged.
 * @param columns mapping tuple field names to Kiji column names.
 */
@ApiAudience.Framework
@ApiStability.Experimental
class KijiScheme(
    private val timeRange: TimeRange,
    private val timestampField: Option[Symbol],
    private val loggingInterval: Long,
    private val columns: Map[String, ColumnRequest])
    extends Scheme[JobConf, RecordReader[KijiKey, KijiValue], OutputCollector[_, _],
        KijiValue, KijiTableWriter] {
  import KijiScheme._

  // Hadoop mapred counters for the rows read from this scheme.
  private val counterGroupName = "kiji-chopsticks"
  private val counterSuccess = "ROWS_READ"
  private val counterMissingField = "ROWS_SKIPPED_WITH_MISSING_FIELDS"

  // The number of skipped rows to log after.
  // TODO(CHOP-47): Make this configurable.
  private val logEvery: Int = 1000

  // Keeps track of how many rows have been skipped, for logging purposes.
  private val skippedRows: AtomicLong = new AtomicLong()

  /** Set the fields that should be in a tuple when this source is used for reading and writing. */
  setSourceFields(buildSourceFields(columns.keys))
  setSinkFields(buildSinkFields(columns.keys, timestampField))

  /**
   * Sets any configuration options that are required for running a MapReduce job
   * that reads from a Kiji table. This method gets called on the client machine
   * during job setup.
   *
   * @param process Current Cascading flow being built.
   * @param tap The tap that is being used with this scheme.
   * @param conf The job configuration object.
   */
  override def sourceConfInit(
      process: FlowProcess[JobConf],
      tap: Tap[JobConf, RecordReader[KijiKey, KijiValue], OutputCollector[_, _]],
      conf: JobConf) {
    // Build a data request.
    val request: KijiDataRequest = buildRequest(timeRange, columns.values)

    // Write all the required values to the job's configuration object.
    conf.setInputFormat(classOf[KijiInputFormat])
    conf.set(
        KijiConfKeys.KIJI_INPUT_DATA_REQUEST,
        Base64.encodeBase64String(SerializationUtils.serialize(request)))
  }

  /**
   * Sets up any resources required for the MapReduce job. This method is called
   * on the cluster.
   *
   * @param process Current Cascading flow being run.
   * @param sourceCall Object containing the context for this source.
   */
  override def sourcePrepare(
      process: FlowProcess[JobConf],
      sourceCall: SourceCall[KijiValue, RecordReader[KijiKey, KijiValue]]) {
    sourceCall.setContext(sourceCall.getInput().createValue())
  }

  /**
   * Reads and converts a row from a Kiji table to a Cascading Tuple. This method
   * is called once for each row on the cluster.
   *
   * @param process Current Cascading flow being run.
   * @param sourceCall Object containing the context for this source.
   * @return True always. This is used to indicate if there are more rows to read.
   */
  override def source(
      process: FlowProcess[JobConf],
      sourceCall: SourceCall[KijiValue, RecordReader[KijiKey, KijiValue]]): Boolean = {
    // Get the current key/value pair.
    val value: KijiValue = sourceCall.getContext()

    // Get the first row where all the requested columns are present,
    // and use that to set the result tuple.
    // Return true as soon as a result tuple has been set,
    // or false if we reach the end of the RecordReader.

    // scalastyle:off null
    while (sourceCall.getInput().next(null, value)) {
    // scalastyle:on null
      val row: KijiRowData = value.get()
      val result: Option[Tuple] = rowToTuple(columns, getSourceFields, timestampField, row)

      // If no fields were missing, set the result tuple and return from this method.
      result match {
        case Some(tuple) => {
          sourceCall.getIncomingEntry().setTuple(tuple)
          process.increment(counterGroupName, counterSuccess, 1)
          return true // We set a result tuple, return true for success.
        }
        case None => {
          // Otherwise, this row was missing fields.
          // Increment the counter for rows with missing fields.
          process.increment(counterGroupName, counterMissingField, 1)
          if (skippedRows.getAndIncrement() % loggingInterval == 0) {
            logger.warn("Row %s skipped because of missing field(s)."
                .format(row.getEntityId.toShellString))
          }
        }
      }
      // We didn't return true because this row was missing fields; continue the loop.
    }
    return false // We reached the end of the RecordReader.
  }

  /**
   * Cleans up any resources used during the MapReduce job. This method is called
   * on the cluster.
   *
   * @param process Current Cascading flow being run.
   * @param sourceCall Object containing the context for this source.
   */
  override def sourceCleanup(
      process: FlowProcess[JobConf],
      sourceCall: SourceCall[KijiValue, RecordReader[KijiKey, KijiValue]]) {
    // scalastyle:off null
    sourceCall.setContext(null)
    // scalastyle:on null
  }

  /**
   * Sets any configuration options that are required for running a MapReduce job
   * that writes to a Kiji table. This method gets called on the client machine
   * during job setup.
   *
   * @param process Current Cascading flow being built.
   * @param tap The tap that is being used with this scheme.
   * @param conf The job configuration object.
   */
  override def sinkConfInit(
      process: FlowProcess[JobConf],
      tap: Tap[JobConf, RecordReader[KijiKey, KijiValue], OutputCollector[_, _]],
      conf: JobConf) {
    // No-op since no configuration parameters need to be set to encode data for Kiji.
  }

  /**
   * Sets up any resources required for the MapReduce job. This method is called
   * on the cluster.
   *
   * @param process Current Cascading flow being run.
   * @param sinkCall Object containing the context for this source.
   */
  override def sinkPrepare(
      process: FlowProcess[JobConf],
      sinkCall: SinkCall[KijiTableWriter, OutputCollector[_, _]]) {
    // Open a table writer.
    val uriString: String = process.getConfigCopy().get(KijiConfKeys.KIJI_OUTPUT_TABLE_URI)
    val uri: KijiURI = KijiURI.newBuilder(uriString).build()

    // TODO: Check and see if Kiji.Factory.open should be passed the configuration object in
    //     process.
    doAndRelease(Kiji.Factory.open(uri)) { kiji: Kiji =>
      doAndRelease(kiji.openTable(uri.getTable())) { table: KijiTable =>
        // Set the sink context to an opened KijiTableWriter.
        sinkCall.setContext(table.openTableWriter())
      }
    }
  }

  /**
   * Converts and writes a Cascading Tuple to a Kiji table. This method is called once
   * for each row on the cluster.
   *
   * @param process Current Cascading flow being run.
   * @param sinkCall Object containing the context for this source.
   */
  override def sink(
      process: FlowProcess[JobConf],
      sinkCall: SinkCall[KijiTableWriter, OutputCollector[_, _]]) {
    // Retrieve writer from the scheme's context.
    val writer: KijiTableWriter = sinkCall.getContext()

    // Write the tuple out.
    val output: TupleEntry = sinkCall.getOutgoingEntry()
    putTuple(columns, getSinkFields(), timestampField, output, writer)
  }

  /**
   * Cleans up any resources used during the MapReduce job. This method is called
   * on the cluster.
   *
   * @param process Current Cascading flow being run.
   * @param sinkCall Object containing the context for this source.
   */
  override def sinkCleanup(
      process: FlowProcess[JobConf],
      sinkCall: SinkCall[KijiTableWriter, OutputCollector[_, _]]) {
    // Close the writer.
    sinkCall.getContext().close()
    // scalastyle:off null
    sinkCall.setContext(null)
    // scalastyle:on null
  }

  override def equals(other: Any): Boolean = {
    other match {
      case scheme: KijiScheme => {
        columns == scheme.columns &&
            timestampField == scheme.timestampField &&
            timeRange == scheme.timeRange
      }
      case _ => false
    }
  }

  override def hashCode(): Int =
      Objects.hashCode(columns, timeRange, timestampField, loggingInterval: java.lang.Long)
}

/** Companion object for KijiScheme. Contains helper methods and constants. */
object KijiScheme {
  private val logger: Logger = LoggerFactory.getLogger(classOf[KijiScheme])

  // Hadoop mapred counters for the rows read from this scheme.
  private[chopsticks] val counterGroupName = "kiji-chopsticks"
  // Counter name for the number of rows successfully read.
  private[chopsticks] val counterSuccess = "ROWS_SUCCESSFULLY_READ"
  // Counter name for the number of rows skipped because of missing fields.
  private[chopsticks] val counterMissingField = "ROWS_SKIPPED_WITH_MISSING_FIELDS"

  /** Field name containing a row's [[EntityId]]. */
  private[chopsticks] val entityIdField: String = "entityId"

  /**
   * Converts a KijiRowData to a Cascading tuple, or None if one of the columns didn't exist
   * and no replacement was specified.
   *
   * @param columns Mapping from field name to column definition.
   * @param fields Field names of desired tuple elements.
   * @param row The row data.
   * @param timestampField TODO
   * @return A tuple containing the values contained in the specified row, or None if some columns
   *     didn't exist and no replacement was specified.
   */
  private[chopsticks] def rowToTuple(
      columns: Map[String, ColumnRequest],
      fields: Fields,
      timestampField: Option[Symbol],
      row: KijiRowData): Option[Tuple] = {
    val result: Tuple = new Tuple()
    val iterator = fields.iterator().asScala

    // Add the row's EntityId to the tuple.
    result.add(row.getEntityId())

    // Get rid of the entity id and timestamp fields, then map over each field to add a column
    // to the tuple.
    iterator
        .filter { field => field.toString != entityIdField  }
        .filter { field => field.toString != timestampField.getOrElse(Symbol("")).name }
        .map { field => columns(field.toString) }
        // Build the tuple, by adding each requested value into result.
        .foreach {
            case colReq @ ColumnFamily(family, ColumnRequestOptions(_, _, replacement)) => {
              if (row.containsColumn(family)) {
                result.add(row.getValues(family))
              } else {
                replacement match {
                  case Some(replacementSlice) => {
                    result.add(replacementSlice)
                  }
                  case None =>
                    // this row cannot be converted to a tuple since this column is missing.
                    return None
                }
              }
            }
            case colReq @ QualifiedColumn(
                family,
                qualifier,
                ColumnRequestOptions(_, _, replacement)) => {
              if (row.containsColumn(family, qualifier)) {
                result.add(row.getValues(family, qualifier))
              } else {
                replacement match {
                  case Some(replacementSlice) => {
                    result.add(replacementSlice)
                  }
                  // this row cannot be converted to a tuple since this column is missing.
                  case None => return None
                }
              }
            }
        }

    return Some(result)
  }

  // TODO(CHOP-35): Use an output format that writes to HFiles.
  /**
   * Writes a Cascading tuple to a Kiji table.
   *
   * @param columns Mapping from field name to column definition.
   * @param fields Field names of incoming tuple elements.
   * @param output Tuple to write out.
   * @param writer KijiTableWriter to use to write.
   */
  private[chopsticks] def putTuple(
      columns: Map[String, ColumnRequest],
      fields: Fields,
      timestampField: Option[Symbol],
      output: TupleEntry,
      writer: KijiTableWriter) {
    val iterator = fields.iterator().asScala

    // Get the entityId.
    val entityId: EntityId = output.getObject(entityIdField).asInstanceOf[EntityId]
    iterator.next()

    // Get a timestamp to write the values to, if it was specified by the user.
    val timestamp: Long = timestampField match {
      case Some(field) => {
        iterator.next()
        output.getObject(field.name).asInstanceOf[Long]
      }
      case None => System.currentTimeMillis()
    }

    iterator.foreach { fieldName =>
      columns(fieldName.toString()) match {
        case ColumnFamily(family, _) => {
          writer.put(
              entityId,
              family,
              // scalastyle:off null
              null,
              // scalastyle:on null
              timestamp,
              output.getObject(fieldName.toString()))
        }
        case QualifiedColumn(family, qualifier, _) => {
          writer.put(
              entityId,
              family,
              qualifier,
              timestamp,
              output.getObject(fieldName.toString()))
        }
      }
    }
  }

  private[chopsticks] def buildRequest(
      timeRange: TimeRange,
      columns: Iterable[ColumnRequest]): KijiDataRequest = {
    def addColumn(builder: KijiDataRequestBuilder, column: ColumnRequest) {
      column match {
        case ColumnFamily(family, inputOptions) => {
          builder.newColumnsDef()
              .withMaxVersions(inputOptions.maxVersions)
              // scalastyle:off null
              .withFilter(inputOptions.filter.getOrElse(null))
              // scalastyle:on null
              .add(new KijiColumnName(family))
        }
        case QualifiedColumn(family, qualifier, inputOptions) => {
          builder.newColumnsDef()
              .withMaxVersions(inputOptions.maxVersions)
              // scalastyle:off null
              .withFilter(inputOptions.filter.getOrElse(null))
              // scalastyle:on null
              .add(new KijiColumnName(family, qualifier))
        }
      }
    }

    val requestBuilder: KijiDataRequestBuilder = KijiDataRequest.builder()
        .withTimeRange(timeRange.begin, timeRange.end)

    columns
        .foldLeft(requestBuilder) { (builder, column) =>
          addColumn(builder, column)
          builder
        }
        .build()
  }

  /**
   * Gets a collection of fields by joining two lists of field names and transforming each name
   * into a field.
   *
   * @param headNames is a list of field names.
   * @param tailNames is a list of field names.
   * @return a collection of fields created from the names.
   */
  private def getFieldArray(headNames: Iterable[String], tailNames: Iterable[String]): Fields = {
    Fields.join((headNames ++ tailNames).map { new Fields(_) }.toArray: _*)
  }

  /**
   * Builds the list of tuple fields being read by a scheme. The special field name
   * "entityId" will be included to hold entity ids from the rows of Kiji tables.
   *
   * @param fieldNames is a list of field names that a scheme should read.
   * @return is a collection of fields created from the names.
   */
  private[chopsticks] def buildSourceFields(fieldNames: Iterable[String]): Fields = {
    getFieldArray(Seq(entityIdField), fieldNames)
  }

  /**
   * Builds the list of tuple fields being written by a scheme. The special field name "entityId"
   * will be included to hold entity ids that values should be written to. A timestamp field can
   * also be included, identifying a timestamp that all values will be written to.
   *
   * @param fieldNames is a list of field names that a scheme should write to.
   * @param timestampField is the name of a field containing the timestamp that all values in a
   *     tuple should be written to. Use the empty symbol if all values should be written at the
   *     current time.
   * @return is a collection of fields created from the names.
   */
  private[chopsticks] def buildSinkFields(fieldNames: Iterable[String],
      timestampField: Option[Symbol]): Fields = {
    timestampField match {
      case Some(field) => getFieldArray(Seq(entityIdField, field.name), fieldNames)
      case None => getFieldArray(Seq(entityIdField), fieldNames)
    }
  }
}