/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn.common.write;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.tuple.Pair;

import org.apache.celeborn.common.CelebornConf;
import org.apache.celeborn.common.CommitMetadata;
import org.apache.celeborn.common.protocol.PartitionLocation;
import org.apache.celeborn.common.util.JavaUtils;

public class PushState {

  private final int pushBufferMaxSize;
  public AtomicReference<IOException> exception = new AtomicReference<>();
  private final InFlightRequestTracker inFlightRequestTracker;
  // partition id -> CommitMetadata
  private final ConcurrentHashMap<Integer, CommitMetadata> commitMetadataMap =
      JavaUtils.newConcurrentHashMap();

  private final Map<String, LocationPushFailedBatches> failedBatchMap;

  public PushState(CelebornConf conf) {
    pushBufferMaxSize = conf.clientPushBufferMaxSize();
    inFlightRequestTracker = new InFlightRequestTracker(conf, this);
    failedBatchMap = JavaUtils.newConcurrentHashMap();
  }

  public void cleanup() {
    inFlightRequestTracker.cleanup();
  }

  // key: ${primary addr}, ${replica addr} value: list of data batch
  public final ConcurrentHashMap<Pair<String, String>, DataBatches> batchesMap =
      JavaUtils.newConcurrentHashMap();

  public boolean addBatchData(
      Pair<String, String> addressPair, PartitionLocation loc, int batchId, byte[] body) {
    DataBatches batches = batchesMap.computeIfAbsent(addressPair, (s) -> new DataBatches());
    batches.addDataBatch(loc, batchId, body);
    return batches.getTotalSize() > pushBufferMaxSize;
  }

  public DataBatches takeDataBatches(Pair<String, String> addressPair) {
    return batchesMap.remove(addressPair);
  }

  public int nextBatchId() {
    return inFlightRequestTracker.nextBatchId();
  }

  public void addBatch(int batchId, int batchBytesSize, String hostAndPushPort) {
    inFlightRequestTracker.addBatch(batchId, batchBytesSize, hostAndPushPort);
  }

  public void removeBatch(int batchId, String hostAndPushPort) {
    inFlightRequestTracker.removeBatch(batchId, hostAndPushPort);
  }

  public void onSuccess(String hostAndPushPort) {
    inFlightRequestTracker.onSuccess(hostAndPushPort);
  }

  public void onCongestControl(String hostAndPushPort) {
    inFlightRequestTracker.onCongestControl(hostAndPushPort);
  }

  public boolean limitMaxInFlight(String hostAndPushPort) throws IOException {
    return inFlightRequestTracker.limitMaxInFlight(hostAndPushPort);
  }

  public boolean limitZeroInFlight() throws IOException {
    return inFlightRequestTracker.limitZeroInFlight();
  }

  public int remainingAllowPushes(String hostAndPushPort) {
    return inFlightRequestTracker.remainingAllowPushes(hostAndPushPort);
  }

  public void recordFailedBatch(String partitionId, int mapId, int attemptId, int batchId) {
    this.failedBatchMap
        .computeIfAbsent(partitionId, (s) -> new LocationPushFailedBatches())
        .addFailedBatch(mapId, attemptId, batchId);
  }

  public Map<String, LocationPushFailedBatches> getFailedBatches() {
    return this.failedBatchMap;
  }

  public int[] getCRC32PerPartition(boolean shuffleIntegrityCheckEnabled, int numPartitions) {
    if (!shuffleIntegrityCheckEnabled) {
      return new int[0];
    }

    int[] crc32PerPartition = new int[numPartitions];
    for (Map.Entry<Integer, CommitMetadata> entry : commitMetadataMap.entrySet()) {
      crc32PerPartition[entry.getKey()] = entry.getValue().getChecksum();
    }
    return crc32PerPartition;
  }

  public long[] getBytesWrittenPerPartition(
      boolean shuffleIntegrityCheckEnabled, int numPartitions) {
    if (!shuffleIntegrityCheckEnabled) {
      return new long[0];
    }
    long[] bytesWrittenPerPartition = new long[numPartitions];
    for (Map.Entry<Integer, CommitMetadata> entry : commitMetadataMap.entrySet()) {
      bytesWrittenPerPartition[entry.getKey()] = entry.getValue().getBytes();
    }
    return bytesWrittenPerPartition;
  }

  public void addDataWithOffsetAndLength(int partitionId, byte[] data, int offset, int length) {
    CommitMetadata commitMetadata =
        commitMetadataMap.computeIfAbsent(partitionId, id -> new CommitMetadata());
    commitMetadata.addDataWithOffsetAndLength(data, offset, length);
  }
}
