/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.integrations.destination.s3.csv;

import alex.mojaki.s3upload.MultiPartOutputStream;
import alex.mojaki.s3upload.StreamTransferManager;
import com.amazonaws.services.s3.AmazonS3;
import io.airbyte.integrations.destination.s3.S3DestinationConfig;
import io.airbyte.integrations.destination.s3.S3Format;
import io.airbyte.integrations.destination.s3.writer.BaseS3Writer;
import io.airbyte.integrations.destination.s3.writer.S3Writer;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.ConfiguredAirbyteStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class S3CsvWriter extends BaseS3Writer implements S3Writer {

  private static final Logger LOGGER = LoggerFactory.getLogger(S3CsvWriter.class);

  private final CsvSheetGenerator csvSheetGenerator;
  private final StreamTransferManager uploadManager;
  private final MultiPartOutputStream outputStream;
  private final CSVPrinter csvPrinter;

  public S3CsvWriter(S3DestinationConfig config,
                     AmazonS3 s3Client,
                     ConfiguredAirbyteStream configuredStream,
                     Timestamp uploadTimestamp)
      throws IOException {
    super(config, s3Client, configuredStream);

    S3CsvFormatConfig formatConfig = (S3CsvFormatConfig) config.getFormatConfig();
    this.csvSheetGenerator = CsvSheetGenerator.Factory.create(configuredStream.getStream().getJsonSchema(),
        formatConfig);

    String outputFilename = BaseS3Writer.getOutputFilename(uploadTimestamp, S3Format.CSV);
    String objectKey = String.join("/", outputPrefix, outputFilename);

    LOGGER.info("Full S3 path for stream '{}': {}/{}", stream.getName(), config.getBucketName(),
        objectKey);

    // The stream transfer manager lets us greedily stream into S3. The native AWS SDK does not
    // have support for streaming multipart uploads. The alternative is first writing the entire
    // output to disk before loading into S3. This is not feasible with large input.
    // Data is chunked into parts during the upload. A part is sent off to a queue to be uploaded
    // once it has reached it's configured part size.
    // See {@link S3DestinationConstants} for memory usage calculation.
    this.uploadManager = new StreamTransferManager(config.getBucketName(), objectKey, s3Client)
        .numStreams(S3CsvConstants.DEFAULT_NUM_STREAMS)
        .queueCapacity(S3CsvConstants.DEFAULT_QUEUE_CAPACITY)
        .numUploadThreads(S3CsvConstants.DEFAULT_UPLOAD_THREADS)
        .partSize(S3CsvConstants.DEFAULT_PART_SIZE_MB);
    // We only need one output stream as we only have one input stream. This is reasonably performant.
    this.outputStream = uploadManager.getMultiPartOutputStreams().get(0);
    this.csvPrinter = new CSVPrinter(new PrintWriter(outputStream, true, StandardCharsets.UTF_8),
        CSVFormat.DEFAULT.withQuoteMode(QuoteMode.ALL)
            .withHeader(csvSheetGenerator.getHeaderRow().toArray(new String[0])));
  }

  @Override
  public void write(UUID id, AirbyteRecordMessage recordMessage) throws IOException {
    csvPrinter.printRecord(csvSheetGenerator.getDataRow(id, recordMessage));
  }

  @Override
  public void close(boolean hasFailed) throws IOException {
    csvPrinter.close();
    outputStream.close();

    if (hasFailed) {
      LOGGER.warn("Failure detected. Aborting upload of stream '{}'...", stream.getName());
      uploadManager.abort();
      LOGGER.warn("Upload of stream '{}' aborted.", stream.getName());
    } else {
      LOGGER.info("Uploading remaining data for stream '{}'.", stream.getName());
      uploadManager.complete();
      LOGGER.info("Upload completed for stream '{}'.", stream.getName());
    }
  }

}
