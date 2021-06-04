/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.realtime.appenderator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.druid.client.cache.Cache;
import org.apache.druid.data.input.Committer;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.RE;
import org.apache.druid.java.util.common.RetryUtils;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QuerySegmentWalker;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.IndexMerger;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.QueryableIndexSegment;
import org.apache.druid.segment.ReferenceCountingSegment;
import org.apache.druid.segment.incremental.IncrementalIndexAddResult;
import org.apache.druid.segment.incremental.IndexSizeExceededException;
import org.apache.druid.segment.incremental.ParseExceptionHandler;
import org.apache.druid.segment.incremental.RowIngestionMeters;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.loading.DataSegmentPusher;
import org.apache.druid.segment.realtime.FireDepartmentMetrics;
import org.apache.druid.segment.realtime.FireHydrant;
import org.apache.druid.segment.realtime.plumber.Sink;
import org.apache.druid.server.coordination.DataSegmentAnnouncer;
import org.apache.druid.timeline.DataSegment;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class BatchAppenderator implements Appenderator
{
  public static final int ROUGH_OVERHEAD_PER_SINK = 5000;
  // Rough estimate of memory footprint of empty FireHydrant based on actual heap dumps
  public static final int ROUGH_OVERHEAD_PER_HYDRANT = 1000;

  private static final EmittingLogger log = new EmittingLogger(BatchAppenderator.class);
  private static final int WARN_DELAY = 1000;
  private static final String IDENTIFIER_FILE_NAME = "identifier.json";

  private final String myId;
  private final DataSchema schema;
  private final AppenderatorConfig tuningConfig;
  private final FireDepartmentMetrics metrics;
  private final DataSegmentPusher dataSegmentPusher;
  private final ObjectMapper objectMapper;
  private final DataSegmentAnnouncer segmentAnnouncer;
  private final IndexIO indexIO;
  private final IndexMerger indexMerger;
  private final Cache cache;
  /**
   * This map needs to be concurrent because it's accessed and mutated from multiple threads: both the thread from where
   * this Appenderator is used (and methods like {@link #add(SegmentIdWithShardSpec, InputRow, Supplier, boolean)} are
   * called) and from {@link #persistExecutor}. It could also be accessed (but not mutated) potentially in the context
   * of any thread from {@link #drop}.
   */
  private final ConcurrentMap<SegmentIdWithShardSpec, Sink> sinks = new ConcurrentHashMap<>();
  private final long maxBytesTuningConfig;
  private final boolean skipBytesInMemoryOverheadCheck;

  /**
   * The following sinks metadata map and associated class are the way to retain metadata now that sinks
   * are being completely removed from memory after each incremental persist. For now, {@link SinkMetadata} only
   * contains a single memeber {@link SinkMetadata#numRowsInSegment} but we can add more in the future as needed
   */
  private final ConcurrentHashMap<SegmentIdWithShardSpec, SinkMetadata> sinksMetadata = new ConcurrentHashMap<>();

  /**
   * This class is used for information that needs to be kept related to Sinks as
   * they are persisted and removed from memory at every incremental persist.
   * The information is used for sanity checks and as information required
   * for functionality, depending in the field that is used. More info about the
   * fields is annotated as comments in the class
   */
  private static class SinkMetadata
  {
    /** This is used to maintain the rows in the sink accross persists of the sink
    // used for functionality (i.e. to detect whether an incremental push
    // is needed {@link AppenderatorDriverAddResult#isPushRequired(Integer, Long)} 
    **/
    private int numRowsInSegment;
    /** For sanity check to make sure that all hydrants for a sink are restored from disk at
     * push time
     */
    private int numHydrants;
    /** Sinks when they are persisted lose information about the previous hydrant count,
     * this variable remembers that so the proper directory can be created when persisting
     * hydrants
     */
    private int previousHydrantCount;

    public SinkMetadata()
    {
      this(0,0,0);
    }

    public SinkMetadata(int numRowsInSegment, int numHydrants, int previousHydrantCount)
    {
      this.numRowsInSegment = numRowsInSegment;
      this.numHydrants = numHydrants;
      this.previousHydrantCount = previousHydrantCount;

    }

    public void addRows(int num)
    {
      numRowsInSegment += num;
    }

    public void addHydrants(int num)
    {
      numHydrants += num;
    }

    public int getNumRowsInSegment()
    {
      return numRowsInSegment;
    }

    public int getNumHydrants()
    {
      return numHydrants;
    }

    public void addHydrantCount(int num) {
      previousHydrantCount += num;
    }
    public int getHydrantCount() {
      return previousHydrantCount;
    }
  }


  private final QuerySegmentWalker texasRanger;
  // This variable updated in add(), persist(), and drop()
  private final AtomicInteger rowsCurrentlyInMemory = new AtomicInteger();
  private final AtomicInteger totalRows = new AtomicInteger();
  private final AtomicLong bytesCurrentlyInMemory = new AtomicLong();
  private final RowIngestionMeters rowIngestionMeters;
  private final ParseExceptionHandler parseExceptionHandler;
  // Synchronize persisting commitMetadata so that multiple persist threads (if present)
  // and abandon threads do not step over each other
  private final Lock commitLock = new ReentrantLock();

  private final AtomicBoolean closed = new AtomicBoolean(false);

  private volatile ListeningExecutorService persistExecutor = null;
  private volatile ListeningExecutorService pushExecutor = null;
  // use intermediate executor so that deadlock conditions can be prevented
  // where persist and push Executor try to put tasks in each other queues
  // thus creating circular dependency
  private volatile ListeningExecutorService intermediateTempExecutor = null;
  private volatile long nextFlush;
  private volatile FileLock basePersistDirLock = null;
  private volatile FileChannel basePersistDirLockChannel = null;

  private volatile Throwable persistError;

  /**
   * This constructor allows the caller to provide its own SinkQuerySegmentWalker.
   * <p>
   * The sinkTimeline is set to the sink timeline of the provided SinkQuerySegmentWalker.
   * If the SinkQuerySegmentWalker is null, a new sink timeline is initialized.
   * <p>
   * It is used by UnifiedIndexerAppenderatorsManager which allows queries on data associated with multiple
   * Appenderators.
   */
  BatchAppenderator(
      String id,
      DataSchema schema,
      AppenderatorConfig tuningConfig,
      FireDepartmentMetrics metrics,
      DataSegmentPusher dataSegmentPusher,
      ObjectMapper objectMapper,
      DataSegmentAnnouncer segmentAnnouncer,
      @Nullable SinkQuerySegmentWalker sinkQuerySegmentWalker,
      IndexIO indexIO,
      IndexMerger indexMerger,
      Cache cache,
      RowIngestionMeters rowIngestionMeters,
      ParseExceptionHandler parseExceptionHandler
  )
  {
    Preconditions.checkArgument(
        sinkQuerySegmentWalker == null,
        "Batch appenderator does not use a versioned timeline"
    );

    this.myId = id;
    this.schema = Preconditions.checkNotNull(schema, "schema");
    this.tuningConfig = Preconditions.checkNotNull(tuningConfig, "tuningConfig");
    this.metrics = Preconditions.checkNotNull(metrics, "metrics");
    this.dataSegmentPusher = Preconditions.checkNotNull(dataSegmentPusher, "dataSegmentPusher");
    this.objectMapper = Preconditions.checkNotNull(objectMapper, "objectMapper");
    this.segmentAnnouncer = Preconditions.checkNotNull(segmentAnnouncer, "segmentAnnouncer");
    this.indexIO = Preconditions.checkNotNull(indexIO, "indexIO");
    this.indexMerger = Preconditions.checkNotNull(indexMerger, "indexMerger");
    this.cache = cache;
    this.texasRanger = sinkQuerySegmentWalker;
    this.rowIngestionMeters = Preconditions.checkNotNull(rowIngestionMeters, "rowIngestionMeters");
    this.parseExceptionHandler = Preconditions.checkNotNull(parseExceptionHandler, "parseExceptionHandler");

    maxBytesTuningConfig = tuningConfig.getMaxBytesInMemoryOrDefault();
    skipBytesInMemoryOverheadCheck = tuningConfig.isSkipBytesInMemoryOverheadCheck();
  }

  @Override
  public String getId()
  {
    return myId;
  }

  @Override
  public String getDataSource()
  {
    return schema.getDataSource();
  }

  @Override
  public Object startJob()
  {
    tuningConfig.getBasePersistDirectory().mkdirs();
    lockBasePersistDirectory();
    initializeExecutors();
    resetNextFlush();
    return null;
  }

  private void throwPersistErrorIfExists()
  {
    if (persistError != null) {
      throw new RE(persistError, "Error while persisting");
    }
  }

  @Override
  public AppenderatorAddResult add(
      final SegmentIdWithShardSpec identifier,
      final InputRow row,
      @Nullable final Supplier<Committer> committerSupplier,
      final boolean allowIncrementalPersists
  ) throws IndexSizeExceededException, SegmentNotWritableException
  {

    throwPersistErrorIfExists();

    Preconditions.checkArgument(
        committerSupplier == null,
        "Batch appenderator does not need a committer!"
    );

    Preconditions.checkArgument(
        allowIncrementalPersists,
        "Batch appenderator should always allow incremental persists!"
    );

    if (!identifier.getDataSource().equals(schema.getDataSource())) {
      throw new IAE(
          "Expected dataSource[%s] but was asked to insert row for dataSource[%s]?!",
          schema.getDataSource(),
          identifier.getDataSource()
      );
    }

    final Sink sink = getOrCreateSink(identifier);
    metrics.reportMessageMaxTimestamp(row.getTimestampFromEpoch());
    final int sinkRowsInMemoryBeforeAdd = sink.getNumRowsInMemory();
    final int sinkRowsInMemoryAfterAdd;
    final long bytesInMemoryBeforeAdd = sink.getBytesInMemory();
    final long bytesInMemoryAfterAdd;
    final IncrementalIndexAddResult addResult;

    try {
      addResult = sink.add(row, !allowIncrementalPersists);
      sinkRowsInMemoryAfterAdd = addResult.getRowCount();
      bytesInMemoryAfterAdd = addResult.getBytesInMemory();
    }
    catch (IndexSizeExceededException e) {
      // Uh oh, we can't do anything about this! We can't persist (commit metadata would be out of sync) and we
      // can't add the row (it just failed). This should never actually happen, though, because we check
      // sink.canAddRow after returning from add.
      log.error(e, "Sink for segment[%s] was unexpectedly full!", identifier);
      throw e;
    }

    if (sinkRowsInMemoryAfterAdd < 0) {
      throw new SegmentNotWritableException("Attempt to add row to swapped-out sink for segment[%s].", identifier);
    }

    if (addResult.isRowAdded()) {
      rowIngestionMeters.incrementProcessed();
    } else if (addResult.hasParseException()) {
      parseExceptionHandler.handle(addResult.getParseException());
    }

    final int numAddedRows = sinkRowsInMemoryAfterAdd - sinkRowsInMemoryBeforeAdd;
    rowsCurrentlyInMemory.addAndGet(numAddedRows);
    bytesCurrentlyInMemory.addAndGet(bytesInMemoryAfterAdd - bytesInMemoryBeforeAdd);
    totalRows.addAndGet(numAddedRows);
    sinksMetadata.computeIfAbsent(identifier, Void -> new SinkMetadata()).addRows(numAddedRows);

    boolean isPersistRequired = false;
    boolean persist = false;
    List<String> persistReasons = new ArrayList<>();

    if (!sink.canAppendRow()) {
      persist = true;
      persistReasons.add("No more rows can be appended to sink");
    }
    if (System.currentTimeMillis() > nextFlush) {
      persist = true;
      persistReasons.add(StringUtils.format(
          "current time[%d] is greater than nextFlush[%d]",
          System.currentTimeMillis(),
          nextFlush
      ));
    }
    if (rowsCurrentlyInMemory.get() >= tuningConfig.getMaxRowsInMemory()) {
      persist = true;
      persistReasons.add(StringUtils.format(
          "rowsCurrentlyInMemory[%d] is greater than maxRowsInMemory[%d]",
          rowsCurrentlyInMemory.get(),
          tuningConfig.getMaxRowsInMemory()
      ));
    }
    if (bytesCurrentlyInMemory.get() >= maxBytesTuningConfig) {
      persist = true;
      persistReasons.add(StringUtils.format(
          "bytesCurrentlyInMemory[%d] is greater than maxBytesInMemory[%d]",
          bytesCurrentlyInMemory.get(),
          maxBytesTuningConfig
      ));
    }
    if (persist) {
      if (allowIncrementalPersists) {
        // persistAll clears rowsCurrentlyInMemory, no need to update it.
        log.info("Incremental persist to disk because %s.", String.join(",", persistReasons));

        long bytesToBePersisted = 0L;
        for (Map.Entry<SegmentIdWithShardSpec, Sink> entry : sinks.entrySet()) {
          final Sink sinkEntry = entry.getValue();
          if (sinkEntry != null) {
            bytesToBePersisted += sinkEntry.getBytesInMemory();
            if (sinkEntry.swappable()) {
              // Code for batch no longer memory maps hydrants but they still take memory...
              int memoryStillInUse = calculateMemoryUsedByHydrants(sink.getCurrHydrant());
              bytesCurrentlyInMemory.addAndGet(memoryStillInUse);
            }
          }
        }

        if (!skipBytesInMemoryOverheadCheck
            && bytesCurrentlyInMemory.get() - bytesToBePersisted > maxBytesTuningConfig) {
          // We are still over maxBytesTuningConfig even after persisting.
          // This means that we ran out of all available memory to ingest (due to overheads created as part of ingestion)
          final String alertMessage = StringUtils.format(
              "Task has exceeded safe estimated heap usage limits, failing "
              + "(numSinks: [%d] numHydrantsAcrossAllSinks: [%d] totalRows: [%d])"
              + "(bytesCurrentlyInMemory: [%d] - bytesToBePersisted: [%d] > maxBytesTuningConfig: [%d])",
              sinks.size(),
              sinks.values().stream().mapToInt(Iterables::size).sum(),
              getTotalRowCount(),
              bytesCurrentlyInMemory.get(),
              bytesToBePersisted,
              maxBytesTuningConfig
          );
          final String errorMessage = StringUtils.format(
              "%s.\nThis can occur when the overhead from too many intermediary segment persists becomes to "
              + "great to have enough space to process additional input rows. This check, along with metering the overhead "
              + "of these objects to factor into the 'maxBytesInMemory' computation, can be disabled by setting "
              + "'skipBytesInMemoryOverheadCheck' to 'true' (note that doing so might allow the task to naturally encounter "
              + "a 'java.lang.OutOfMemoryError'). Alternatively, 'maxBytesInMemory' can be increased which will cause an "
              + "increase in heap footprint, but will allow for more intermediary segment persists to occur before "
              + "reaching this condition.",
              alertMessage
          );
          log.makeAlert(alertMessage)
             .addData("dataSource", schema.getDataSource())
             .emit();
          throw new RuntimeException(errorMessage);
        }

        persistAllAndClear();

      } else {
        throw new ISE("Batch appenderator always persists as needed!");
      }
    }
    return new AppenderatorAddResult(identifier, sinksMetadata.get(identifier).numRowsInSegment, isPersistRequired);
  }

  @Override
  public List<SegmentIdWithShardSpec> getSegments()
  {
    return ImmutableList.copyOf(sinks.keySet());
  }

  @Override
  public int getRowCount(final SegmentIdWithShardSpec identifier)
  {
    final Sink sink = sinks.get(identifier);

    if (sink == null) {
      throw new ISE("No such sink: %s", identifier);
    } else {
      return sink.getNumRows();
    }
  }

  @Override
  public int getTotalRowCount()
  {
    return totalRows.get();
  }

  @VisibleForTesting
  public int getRowsInMemory()
  {
    return rowsCurrentlyInMemory.get();
  }

  @VisibleForTesting
  public long getBytesCurrentlyInMemory()
  {
    return bytesCurrentlyInMemory.get();
  }

  @VisibleForTesting
  public long getBytesInMemory(SegmentIdWithShardSpec identifier)
  {
    final Sink sink = sinks.get(identifier);

    if (sink == null) {
      return 0L; // sinks are removed after a persist
    } else {
      return sink.getBytesInMemory();
    }
  }

  private Sink getOrCreateSink(final SegmentIdWithShardSpec identifier)
  {
    Sink retVal = sinks.get(identifier);

    if (retVal == null) {
      retVal = new Sink(
          identifier.getInterval(),
          schema,
          identifier.getShardSpec(),
          identifier.getVersion(),
          tuningConfig.getAppendableIndexSpec(),
          tuningConfig.getMaxRowsInMemory(),
          maxBytesTuningConfig,
          null
      );
      bytesCurrentlyInMemory.addAndGet(calculateSinkMemoryInUsed(retVal));

      try {
        segmentAnnouncer.announceSegment(retVal.getSegment());
      }
      catch (IOException e) {
        log.makeAlert(e, "Failed to announce new segment[%s]", schema.getDataSource())
           .addData("interval", retVal.getInterval())
           .emit();
      }

      sinks.put(identifier, retVal);
      metrics.setSinkCount(sinks.size());
    }

    return retVal;
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForIntervals(final Query<T> query, final Iterable<Interval> intervals)
  {
    if (texasRanger == null) {
      throw new IllegalStateException("Don't query me, bro.");
    }

    return texasRanger.getQueryRunnerForIntervals(query, intervals);
  }

  @Override
  public <T> QueryRunner<T> getQueryRunnerForSegments(final Query<T> query, final Iterable<SegmentDescriptor> specs)
  {
    if (texasRanger == null) {
      throw new IllegalStateException("Don't query me, bro.");
    }

    return texasRanger.getQueryRunnerForSegments(query, specs);
  }

  @Override
  public void clear() throws InterruptedException
  {
    clear(true);
  }

  private void clear(boolean removeOnDiskData) throws InterruptedException
  {
    // Drop commit metadata, then abandon all segments.
    log.info("Clearing all sinks & hydrants, removing data on disk: [%s]", removeOnDiskData);
    try {
      throwPersistErrorIfExists();
      // Drop everything.
      final List<ListenableFuture<?>> futures = new ArrayList<>();
      for (Map.Entry<SegmentIdWithShardSpec, Sink> entry : sinks.entrySet()) {
        futures.add(removeSink(entry.getKey(), entry.getValue(), removeOnDiskData));
      }
      // Await dropping.
      Futures.allAsList(futures).get();
    }
    catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public ListenableFuture<?> drop(final SegmentIdWithShardSpec identifier)
  {
    final Sink sink = sinks.get(identifier);
    SinkMetadata sm = sinksMetadata.remove(identifier);
    if (sm != null) {
      int originalTotalRows = getTotalRowCount();
      int rowsToDrop = sm.getNumRowsInSegment();
      int totalRowsAfter = originalTotalRows - rowsToDrop;
      if (totalRowsAfter < 0) {
        log.warn("Total rows[%d] after dropping segment[%s] rows [%d]", totalRowsAfter, identifier, rowsToDrop);
      }
      totalRows.set(Math.max(totalRowsAfter, 0));
    }
    if (sink != null) {
      return removeSink(identifier, sink, true);
    } else {
      return Futures.immediateFuture(null);
    }
  }

  private SegmentsAndCommitMetadata persistAllAndClear()
  {
    final ListenableFuture<Object> toPersist = Futures.transform(
        persistAll(null),
        (Function<Object, Object>) future -> future
    );

    // make sure sinks are cleared before push is called
    final SegmentsAndCommitMetadata commitMetadata;
    try {
      commitMetadata = (SegmentsAndCommitMetadata) toPersist.get();
      clear(false);
      return commitMetadata;
    }
    catch (Throwable t) {
      persistError = t;
    }
    return null;
  }

  @Override
  public ListenableFuture<Object> persistAll(@Nullable final Committer committer)
  {
    throwPersistErrorIfExists();
    final List<Pair<FireHydrant, SegmentIdWithShardSpec>> indexesToPersist = new ArrayList<>();
    int numPersistedRows = 0;
    long bytesPersisted = 0L;
    MutableInt totalHydrantsCount = new MutableInt();
    MutableInt totalHydrantsPersistedAcrossSinks = new MutableInt();
    SegmentIdWithShardSpec startIdentifier = null;
    final long totalSinks = sinks.size();
    for (Map.Entry<SegmentIdWithShardSpec, Sink> entry : sinks.entrySet()) {
      final SegmentIdWithShardSpec identifier = entry.getKey();
      final Sink sink = entry.getValue();
      if (sink == null) {
        throw new ISE("No sink for identifier: %s", identifier);
      }

      int previousHydrantCount = totalHydrantsPersistedAcrossSinks.intValue();

      final List<FireHydrant> hydrants = Lists.newArrayList(sink);
      totalHydrantsCount.add(hydrants.size());
      numPersistedRows += sink.getNumRowsInMemory();
      bytesPersisted += sink.getBytesInMemory();

      final int limit = sink.isWritable() ? hydrants.size() - 1 : hydrants.size();

      // gather hydrants that have not been persisted:
      for (FireHydrant hydrant : hydrants.subList(0, limit)) {
        if (!hydrant.hasSwapped()) {
          log.debug("Hydrant[%s] hasn't persisted yet, persisting. Segment[%s]", hydrant, identifier);
          indexesToPersist.add(Pair.of(hydrant, identifier));
          totalHydrantsPersistedAcrossSinks.add(1);
        }
      }

      if (sink.swappable()) {
        // It is swappable. Get the old one to persist it and create a new one:
        indexesToPersist.add(Pair.of(sink.swap(), identifier));
        totalHydrantsPersistedAcrossSinks.add(1);
      }

      // keep track of hydrants for sanity when resurrecting before push
      sinksMetadata.computeIfAbsent(
          identifier,
          Void -> new SinkMetadata()
      ).addHydrants(totalHydrantsPersistedAcrossSinks.intValue() - previousHydrantCount);

    }
    log.debug("Submitting persist runnable for dataSource[%s]", schema.getDataSource());

    if (indexesToPersist.isEmpty()) {
      log.info("No indexes will be peristed");
    }
    final Stopwatch runExecStopwatch = Stopwatch.createStarted();
    final Stopwatch persistStopwatch = Stopwatch.createStarted();
    AtomicLong totalPersistedRows = new AtomicLong(numPersistedRows);
    final ListenableFuture<Object> future = persistExecutor.submit(
        new Callable<Object>()
        {
          @Override
          public Object call()
          {
            try {
              for (Pair<FireHydrant, SegmentIdWithShardSpec> pair : indexesToPersist) {
                metrics.incrementRowOutputCount(persistHydrant(pair.lhs, pair.rhs));
              }

              log.info(
                  "Persisted in-memory data for segments: %s",
                  indexesToPersist.stream()
                                  .map(itp -> itp.rhs.asSegmentId().toString())
                                  .distinct()
                                  .collect(Collectors.joining(", "))
              );
              log.info(
                  "Persisted stats: processed rows: [%d], persisted rows[%d], sinks: [%d], total fireHydrants (across sinks): [%d], persisted fireHydrants (across sinks): [%d]",
                  rowIngestionMeters.getProcessed(),
                  totalPersistedRows.get(),
                  totalSinks,
                  totalHydrantsCount.longValue(),
                  totalHydrantsPersistedAcrossSinks.longValue()
              );

              // return null if committer is null
              return null;
            }
            catch (Exception e) {
              metrics.incrementFailedPersists();
              throw e;
            }
            finally {
              metrics.incrementNumPersists();
              metrics.incrementPersistTimeMillis(persistStopwatch.elapsed(TimeUnit.MILLISECONDS));
              persistStopwatch.stop();
            }
          }
        }
    );

    final long startDelay = runExecStopwatch.elapsed(TimeUnit.MILLISECONDS);
    metrics.incrementPersistBackPressureMillis(startDelay);
    if (startDelay > WARN_DELAY) {
      log.warn("Ingestion was throttled for [%,d] millis because persists were pending.", startDelay);
    }
    runExecStopwatch.stop();
    resetNextFlush();

    // NB: The rows are still in memory until they're done persisting, but we only count rows in active indexes.
    rowsCurrentlyInMemory.addAndGet(-numPersistedRows);
    bytesCurrentlyInMemory.addAndGet(-bytesPersisted);

    log.info("Persisted rows[%,d] and bytes[%,d]", numPersistedRows, bytesPersisted);

    return future;
  }

  @Override
  public ListenableFuture<SegmentsAndCommitMetadata> push(
      final Collection<SegmentIdWithShardSpec> identifiers,
      @Nullable final Committer committer,
      final boolean useUniquePath
  )
  {

    if (committer != null) {
      throw new ISE("There should be no committer for batch ingestion");
    }

    // Any sinks not persisted so far will be persisted before push:
    final SegmentsAndCommitMetadata commitMetadata = persistAllAndClear();

    final ListenableFuture<SegmentsAndCommitMetadata> pushFuture = pushExecutor.submit(
        new Callable<SegmentsAndCommitMetadata>()
        {
          @Override
          public SegmentsAndCommitMetadata call()
          {
            log.info("Preparing to push...");

            final List<DataSegment> dataSegments = new ArrayList<>();
            List<File> persistedIdentifiers = getPersistedidentifierPaths();
            for (File identifier : persistedIdentifiers) {
              Pair<SegmentIdWithShardSpec, Sink> identifiersAndSinks = getIdentifierAndSinkForPersistedFile(identifier);
              final DataSegment dataSegment = mergeAndPush(
                  identifiersAndSinks.lhs,
                  identifiersAndSinks.rhs,
                  useUniquePath
              );
              if (dataSegment != null) {
                dataSegments.add(dataSegment);
              } else {
                log.warn("mergeAndPush[%s] returned null, skipping.", identifiersAndSinks.lhs);
              }
            }

            log.info("Push complete...");

            return new SegmentsAndCommitMetadata(dataSegments, commitMetadata);
          }
        });

    return pushFuture;
  }

  /**
   * Insert a barrier into the merge-and-push queue. When this future resolves, all pending pushes will have finished.
   * This is useful if we're going to do something that would otherwise potentially break currently in-progress
   * pushes.
   */
  private ListenableFuture<?> pushBarrier()
  {
    return intermediateTempExecutor.submit(
        (Runnable) () -> pushExecutor.submit(() -> {
        })
    );
  }

  /**
   * Merge segment, push to deep storage. Should only be used on segments that have been fully persisted. Must only
   * be run in the single-threaded pushExecutor.
   *
   * @param identifier    sink identifier
   * @param sink          sink to push
   * @param useUniquePath true if the segment should be written to a path with a unique identifier
   * @return segment descriptor, or null if the sink is no longer valid
   */
  @Nullable
  private DataSegment mergeAndPush(
      final SegmentIdWithShardSpec identifier,
      final Sink sink,
      final boolean useUniquePath
  )
  {

    // Use a descriptor file to indicate that pushing has completed.
    final File persistDir = computePersistDir(identifier);
    final File mergedTarget = new File(persistDir, "merged");
    final File descriptorFile = computeDescriptorFile(identifier);

    // Sanity checks
    if (sink.isWritable()) {
      throw new ISE("Expected sink to be no longer writable before mergeAndPush for segment[%s].", identifier);
    }

    int numHydrants = 0;
    for (FireHydrant hydrant : sink) {
      synchronized (hydrant) {
        if (!hydrant.hasSwapped()) {
          throw new ISE("Expected sink to be fully persisted before mergeAndPush for segment[%s].", identifier);
        }
      }
      numHydrants++;
    }

    SinkMetadata sm = sinksMetadata.get(identifier);
    if (sm == null) {
      log.warn("Sink metadata not found just before merge for identifier [%s]", identifier);
    } else if (numHydrants != sinksMetadata.get(identifier).getNumHydrants()) {
      throw new ISE("Number of restored hydrants[%d] for identifier[%s] does not match expected value[%d]",
                    numHydrants, identifier, sinksMetadata.get(identifier).getNumHydrants());
    }

    try {
      if (descriptorFile.exists()) {
        // Already pushed.

        if (useUniquePath) {
          // Don't reuse the descriptor, because the caller asked for a unique path. Leave the old one as-is, since
          // it might serve some unknown purpose.
          log.debug(
              "Segment[%s] already pushed, but we want a unique path, so will push again with a new path.",
              identifier
          );
        } else {
          log.info("Segment[%s] already pushed, skipping.", identifier);
          return objectMapper.readValue(descriptorFile, DataSegment.class);
        }
      }

      removeDirectory(mergedTarget);

      if (mergedTarget.exists()) {
        throw new ISE("Merged target[%s] exists after removing?!", mergedTarget);
      }

      final File mergedFile;
      final long mergeFinishTime;
      final long startTime = System.nanoTime();
      List<QueryableIndex> indexes = new ArrayList<>();
      Closer closer = Closer.create();
      try {
        for (FireHydrant fireHydrant : sink) {
          Pair<ReferenceCountingSegment, Closeable> segmentAndCloseable = fireHydrant.getAndIncrementSegment();
          final QueryableIndex queryableIndex = segmentAndCloseable.lhs.asQueryableIndex();
          log.debug("Segment[%s] adding hydrant[%s]", identifier, fireHydrant);
          indexes.add(queryableIndex);
          closer.register(segmentAndCloseable.rhs);
        }

        mergedFile = indexMerger.mergeQueryableIndex(
            indexes,
            schema.getGranularitySpec().isRollup(),
            schema.getAggregators(),
            schema.getDimensionsSpec(),
            mergedTarget,
            tuningConfig.getIndexSpec(),
            tuningConfig.getSegmentWriteOutMediumFactory(),
            tuningConfig.getMaxColumnsToMerge()
        );

        mergeFinishTime = System.nanoTime();

        log.debug("Segment[%s] built in %,dms.", identifier, (mergeFinishTime - startTime) / 1000000);
      }
      catch (Throwable t) {
        throw closer.rethrow(t);
      }
      finally {
        closer.close();
      }

      // Retry pushing segments because uploading to deep storage might fail especially for cloud storage types
      final DataSegment segment = RetryUtils.retry(
          // The appenderator is currently being used for the local indexing task and the Kafka indexing task. For the
          // Kafka indexing task, pushers must use unique file paths in deep storage in order to maintain exactly-once
          // semantics.
          () -> dataSegmentPusher.push(
              mergedFile,
              sink.getSegment()
                  .withDimensions(IndexMerger.getMergedDimensionsFromQueryableIndexes(
                      indexes,
                      schema.getDimensionsSpec()
                  )),
              useUniquePath
          ),
          exception -> exception instanceof Exception,
          5
      );

      // Drop the queryable indexes behind the hydrants... they are not needed anymore and their
      // mapped file references
      // can generate OOMs during merge if enough of them are held back...
      // agfixme: Since we cannot keep sinks due to memory growth then we have to add the sink metadata table and keep it up to date
      //sinks.put(identifier,sink);
      for (FireHydrant fireHydrant : sink) {
        fireHydrant.swapSegment(null);
      }

      // cleanup, sink no longer needed
      removeDirectory(computePersistDir(identifier));

      final long pushFinishTime = System.nanoTime();

      //objectMapper.writeValue(descriptorFile, segment);

      log.info(
          "Segment[%s] of %,d bytes "
          + "built from %d incremental persist(s) in %,dms; "
          + "pushed to deep storage in %,dms. "
          + "Load spec is: %s",
          identifier,
          segment.getSize(),
          indexes.size(),
          (mergeFinishTime - startTime) / 1000000,
          (pushFinishTime - mergeFinishTime) / 1000000,
          objectMapper.writeValueAsString(segment.getLoadSpec())
      );

      return segment;
    }
    catch (Exception e) {
      metrics.incrementFailedHandoffs();
      log.warn(e, "Failed to push merged index for segment[%s].", identifier);
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close()
  {
    if (!closed.compareAndSet(false, true)) {
      log.debug("Appenderator already closed, skipping close() call.");
      return;
    }

    log.debug("Shutting down...");

    final List<ListenableFuture<?>> futures = new ArrayList<>();
    for (Map.Entry<SegmentIdWithShardSpec, Sink> entry : sinks.entrySet()) {
      futures.add(removeSink(entry.getKey(), entry.getValue(), false));
    }

    try {
      Futures.allAsList(futures).get();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn(e, "Interrupted during close()");
    }
    catch (ExecutionException e) {
      log.warn(e, "Unable to abandon existing segments during close()");
    }

    try {
      shutdownExecutors();
      Preconditions.checkState(
          persistExecutor == null || persistExecutor.awaitTermination(365, TimeUnit.DAYS),
          "persistExecutor not terminated"
      );
      Preconditions.checkState(
          pushExecutor == null || pushExecutor.awaitTermination(365, TimeUnit.DAYS),
          "pushExecutor not terminated"
      );
      Preconditions.checkState(
          intermediateTempExecutor == null || intermediateTempExecutor.awaitTermination(365, TimeUnit.DAYS),
          "intermediateTempExecutor not terminated"
      );
      persistExecutor = null;
      pushExecutor = null;
      intermediateTempExecutor = null;

    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ISE("Failed to shutdown executors during close()");
    }

    // Only unlock if executors actually shut down.
    unlockBasePersistDirectory();

    // cleanup:
    List<File> persistedIdentifiers = getPersistedidentifierPaths();
    for (File identifier : persistedIdentifiers) {
      removeDirectory(identifier);
    }

    totalRows.set(0);
    sinksMetadata.clear();
  }

  /**
   * Unannounce the segments and wait for outstanding persists to finish.
   * Do not unlock base persist dir as we are not waiting for push executor to shut down
   * relying on current JVM to shutdown to not cause any locking problem if the task is restored.
   * In case when task is restored and current task is still active because of push executor (which it shouldn't be
   * since push executor starts daemon threads) then the locking should fail and new task should fail to start.
   * This also means that this method should only be called when task is shutting down.
   */
  @Override
  public void closeNow()
  {
    if (!closed.compareAndSet(false, true)) {
      log.debug("Appenderator already closed, skipping closeNow() call.");
      return;
    }

    log.debug("Shutting down immediately...");
    for (Map.Entry<SegmentIdWithShardSpec, Sink> entry : sinks.entrySet()) {
      try {
        segmentAnnouncer.unannounceSegment(entry.getValue().getSegment());
      }
      catch (Exception e) {
        log.makeAlert(e, "Failed to unannounce segment[%s]", schema.getDataSource())
           .addData("identifier", entry.getKey().toString())
           .emit();
      }
    }
    try {
      shutdownExecutors();
      // We don't wait for pushExecutor to be terminated. See Javadoc for more details.
      Preconditions.checkState(
          persistExecutor == null || persistExecutor.awaitTermination(365, TimeUnit.DAYS),
          "persistExecutor not terminated"
      );
      Preconditions.checkState(
          intermediateTempExecutor == null || intermediateTempExecutor.awaitTermination(365, TimeUnit.DAYS),
          "intermediateTempExecutor not terminated"
      );
      persistExecutor = null;
      intermediateTempExecutor = null;
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ISE("Failed to shutdown executors during close()");
    }
  }

  private void lockBasePersistDirectory()
  {
    if (basePersistDirLock == null) {
      try {
        basePersistDirLockChannel = FileChannel.open(
            computeLockFile().toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        );

        basePersistDirLock = basePersistDirLockChannel.tryLock();
        if (basePersistDirLock == null) {
          throw new ISE("Cannot acquire lock on basePersistDir: %s", computeLockFile());
        }
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private void unlockBasePersistDirectory()
  {
    try {
      if (basePersistDirLock != null) {
        basePersistDirLock.release();
        basePersistDirLockChannel.close();
        basePersistDirLock = null;
      }
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void initializeExecutors()
  {
    final int maxPendingPersists = tuningConfig.getMaxPendingPersists();

    if (persistExecutor == null) {
      // use a blocking single threaded executor to throttle the firehose when write to disk is slow
      persistExecutor = MoreExecutors.listeningDecorator(
          Execs.newBlockingSingleThreaded(
              "[" + StringUtils.encodeForFormat(myId) + "]-appenderator-persist",
              maxPendingPersists
          )
      );
    }

    if (pushExecutor == null) {
      // use a blocking single threaded executor to throttle the firehose when write to disk is slow
      pushExecutor = MoreExecutors.listeningDecorator(
          Execs.newBlockingSingleThreaded("[" + StringUtils.encodeForFormat(myId) + "]-appenderator-merge", 1)
      );
    }

    if (intermediateTempExecutor == null) {
      // use single threaded executor with SynchronousQueue so that all abandon operations occur sequentially
      intermediateTempExecutor = MoreExecutors.listeningDecorator(
          Execs.newBlockingSingleThreaded("[" + StringUtils.encodeForFormat(myId) + "]-appenderator-abandon", 0)
      );
    }
  }

  private void shutdownExecutors()
  {
    if (persistExecutor != null) {
      persistExecutor.shutdownNow();
    }

    if (pushExecutor != null) {
      pushExecutor.shutdownNow();
    }

    if (intermediateTempExecutor != null) {
      intermediateTempExecutor.shutdownNow();
    }
  }

  private void resetNextFlush()
  {
    nextFlush = DateTimes.nowUtc().plus(tuningConfig.getIntermediatePersistPeriod()).getMillis();
  }

  @VisibleForTesting
  public List<File> getPersistedidentifierPaths()
  {

    ArrayList<File> retVal = new ArrayList<>();

    final File baseDir = tuningConfig.getBasePersistDirectory();
    if (!baseDir.exists()) {
      return null;
    }

    final File[] files = baseDir.listFiles();
    if (files == null) {
      return null;
    }

    for (File sinkDir : files) {
      final File identifierFile = new File(sinkDir, IDENTIFIER_FILE_NAME);
      if (!identifierFile.isFile()) {
        // No identifier in this sinkDir; it must not actually be a sink directory. Skip it.
        continue;
      }
      retVal.add(sinkDir);
    }

    return retVal;
  }

  Pair<SegmentIdWithShardSpec, Sink> getIdentifierAndSinkForPersistedFile(File identifierPath)
  {

    try {
      final SegmentIdWithShardSpec identifier = objectMapper.readValue(
          new File(identifierPath, "identifier.json"),
          SegmentIdWithShardSpec.class
      );

      // To avoid reading and listing of "merged" dir and other special files
      final File[] sinkFiles = identifierPath.listFiles(
          (dir, fileName) -> !(Ints.tryParse(fileName) == null)
      );

      Arrays.sort(
          sinkFiles,
          (o1, o2) -> Ints.compare(Integer.parseInt(o1.getName()), Integer.parseInt(o2.getName()))
      );

      List<FireHydrant> hydrants = new ArrayList<>();
      for (File hydrantDir : sinkFiles) {
        final int hydrantNumber = Integer.parseInt(hydrantDir.getName());

        log.debug("Loading previously persisted partial segment at [%s]", hydrantDir);
        if (hydrantNumber != hydrants.size()) {
          throw new ISE("Missing hydrant [%,d] in identifier [%s].", hydrants.size(), identifier);
        }

        hydrants.add(
            new FireHydrant(
                new QueryableIndexSegment(indexIO.loadIndex(hydrantDir), identifier.asSegmentId()),
                hydrantNumber
            )
        );
      }

      Sink currSink = new Sink(
          identifier.getInterval(),
          schema,
          identifier.getShardSpec(),
          identifier.getVersion(),
          tuningConfig.getAppendableIndexSpec(),
          tuningConfig.getMaxRowsInMemory(),
          maxBytesTuningConfig,
          null,
          hydrants
      );
      currSink.finishWriting(); // this sink is not writable
      //sinks.put(identifier, currSink);
      return new Pair<>(identifier, currSink);
    }
    catch (IOException e) {
      log.makeAlert(e, "Problem loading sink[%s] from disk.", schema.getDataSource())
         .addData("identifier path", identifierPath)
         .emit();
    }
    return null;
  }

  private ListenableFuture<?> removeSink(
      final SegmentIdWithShardSpec identifier,
      final Sink sink,
      final boolean removeOnDiskData
  )
  {
    // Ensure no future writes will be made to this sink.
    if (sink.finishWriting()) {
      // Decrement this sink's rows from the counters. we only count active sinks so that we don't double decrement,
      // i.e. those that haven't been persisted for *InMemory counters, or pushed to deep storage for the total counter.
      rowsCurrentlyInMemory.addAndGet(-sink.getNumRowsInMemory());
      bytesCurrentlyInMemory.addAndGet(-sink.getBytesInMemory());
      bytesCurrentlyInMemory.addAndGet(-calculateSinkMemoryInUsed(sink));
      for (FireHydrant hydrant : sink) {
        // Decrement memory used by all Memory Mapped Hydrant
        if (!hydrant.equals(sink.getCurrHydrant())) {
          bytesCurrentlyInMemory.addAndGet(-calculateMemoryUsedByHydrants(hydrant));
        }
      }
      // totalRows are not decremented when removing the sink from memory, sink was just persisted and it
      // still "lives" but it is in hibernation. It will be revived later just before push.
    }

    // Wait for any outstanding pushes to finish, then abandon the segment inside the persist thread.
    return Futures.transform(
        pushBarrier(),
        new Function<Object, Void>()
        {
          @Nullable
          @Override
          public Void apply(@Nullable Object input)
          {
            if (!sinks.remove(identifier, sink)) {
              log.error("Sink for segment[%s] no longer valid, not abandoning.", identifier);
              return null;
            }

            metrics.setSinkCount(sinks.size());

            for (FireHydrant hydrant : sink) {
              if (cache != null) {
                cache.close(SinkQuerySegmentWalker.makeHydrantCacheIdentifier(hydrant));
              }
              hydrant.swapSegment(null);
            }

            if (removeOnDiskData) {
              removeDirectory(computePersistDir(identifier));
            }

            log.info("Removed sink for segment[%s].", identifier);

            return null;
          }
        },
        // use persistExecutor to make sure that all the pending persists completes before
        // starting to abandon segments
        persistExecutor
    );
  }

  private File computeLockFile()
  {
    return new File(tuningConfig.getBasePersistDirectory(), ".lock");
  }

  private File computePersistDir(SegmentIdWithShardSpec identifier)
  {
    return new File(tuningConfig.getBasePersistDirectory(), identifier.toString());
  }

  private File computeIdentifierFile(SegmentIdWithShardSpec identifier)
  {
    return new File(computePersistDir(identifier), IDENTIFIER_FILE_NAME);
  }

  private File computeDescriptorFile(SegmentIdWithShardSpec identifier)
  {
    return new File(computePersistDir(identifier), "descriptor.json");
  }

  private File createPersistDirIfNeeded(SegmentIdWithShardSpec identifier) throws IOException
  {
    final File persistDir = computePersistDir(identifier);
    org.apache.commons.io.FileUtils.forceMkdir(persistDir);

    objectMapper.writeValue(computeIdentifierFile(identifier), identifier);

    return persistDir;
  }

  /**
   * Persists the given hydrant and returns the number of rows persisted. Must only be called in the single-threaded
   * persistExecutor.
   *
   * @param indexToPersist hydrant to persist
   * @param identifier     the segment this hydrant is going to be part of
   * @return the number of rows persisted
   */
  private int persistHydrant(FireHydrant indexToPersist, SegmentIdWithShardSpec identifier)
  {
    synchronized (indexToPersist) {
      if (indexToPersist.hasSwapped()) {
        log.info(
            "Segment[%s] hydrant[%s] already swapped. Ignoring request to persist.",
            identifier,
            indexToPersist
        );
        return 0;
      }

      log.debug("Segment[%s], persisting Hydrant[%s]", identifier, indexToPersist);

      try {
        final long startTime = System.nanoTime();
        int numRows = indexToPersist.getIndex().size();

        // since the sink may have been persisted before it may have lost its
        // hydrant count, we remember that value in the sinks metadata so we have
        // to pull it from there....
        SinkMetadata sm = sinksMetadata.get(identifier);
        final File persistDir = createPersistDirIfNeeded(identifier);
        indexMerger.persist(
            indexToPersist.getIndex(),
            identifier.getInterval(),
            new File(persistDir, String.valueOf(sm.getHydrantCount())),
            tuningConfig.getIndexSpecForIntermediatePersists(),
            tuningConfig.getSegmentWriteOutMediumFactory()
        );

        log.info(
            "Persisted in-memory data for segment[%s] spill[%s] to disk in [%,d] ms (%,d rows).",
            indexToPersist.getSegmentId(),
            indexToPersist.getCount(),
            (System.nanoTime() - startTime) / 1000000,
            numRows
        );

        indexToPersist.swapSegment(null);
        // remember hydrant count:
        sinksMetadata.get(identifier).addHydrantCount(1);

        return numRows;
      }
      catch (IOException e) {
        log.makeAlert("Incremental persist failed")
           .addData("segment", identifier.toString())
           .addData("dataSource", schema.getDataSource())
           .addData("count", indexToPersist.getCount())
           .emit();

        throw new RuntimeException(e);
      }
    }
  }

  private void removeDirectory(final File target)
  {
    if (target.exists()) {
      try {
        FileUtils.deleteDirectory(target);
        log.info("Removed directory [%s]", target);
      }
      catch (Exception e) {
        log.makeAlert(e, "Failed to remove directory[%s]", schema.getDataSource())
           .addData("file", target)
           .emit();
      }
    }
  }

  private int calculateMemoryUsedByHydrants(FireHydrant hydrant)
  {
    if (skipBytesInMemoryOverheadCheck) {
      return 0;
    }
    // These calculations are approximated from actual heap dumps.
    // Memory footprint includes count integer in FireHydrant, shorts in ReferenceCountingSegment,
    // Objects in SimpleQueryableIndex (such as SmooshedFileMapper, each ColumnHolder in column map, etc.)
    int total;
    total = Integer.BYTES + (4 * Short.BYTES) + ROUGH_OVERHEAD_PER_HYDRANT;
    return total;
  }

  private int calculateSinkMemoryInUsed(Sink sink)
  {
    if (skipBytesInMemoryOverheadCheck) {
      return 0;
    }
    // Rough estimate of memory footprint of empty Sink based on actual heap dumps
    return ROUGH_OVERHEAD_PER_SINK;
  }
}
